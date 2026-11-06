package ir.vigoapp.storage;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Explicit opt-in only: sh mvnw -Pintegration verify. Real Postgres, Kafka, and HTTP fixtures for JWKS and User. */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StorageIT {
    @org.testcontainers.junit.jupiter.Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @org.testcontainers.junit.jupiter.Container static KafkaContainer broker=new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
    static AuthTokenFixture auth;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        auth=new AuthTokenFixture();
        auth.configure(r);
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers",broker::getBootstrapServers);
        r.add("storage.root",()->Path.of(System.getProperty("basedir","."),"target","it-blobs").toAbsolutePath().toString());
        r.add("storage.outbox-delay-ms",()->"100");
    }
    @AfterAll static void closeFixture(){if(auth!=null)auth.close();}
    @Autowired FileService service;@Autowired JdbcTemplate db;@Autowired SubscriptionConsumer subscriptions;
    @Autowired KafkaTemplate<String,byte[]> kafka;
    @LocalServerPort int port;
    static final ObjectMapper JSON=new ObjectMapper();
    UUID coach=SubscriptionConsumerTest.COACH,athlete=SubscriptionConsumerTest.ATHLETE;
    Policy.Actor owner(){return new Policy.Actor(coach,Policy.Role.COACH);}
    void activate(UUID sub,long version)throws Exception{subscriptions.apply(SubscriptionConsumerTest.bytes(SubscriptionConsumerTest.envelope(sub,version,"SubscriptionActivated","ACTIVE")));}
    MockMultipartFile file(String content){return new MockMultipartFile("file","program.txt","application/evil-client-type",content.getBytes(StandardCharsets.UTF_8));}
    UUID upload(String title)throws Exception{activate(UUID.randomUUID(),1);return UUID.fromString((String)service.upload(owner(),athlete,Policy.Category.WORKOUT,title,null,file("original bytes" )).get("file_id"));}
    HttpResponse<byte[]> request(String path,String method,String token,String body)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("X-User-Id",athlete.toString()).header("X-User-Role","ADMIN");
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(body!=null)b.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test void realJwtPoliciesByteDownloadAndSoftDeleteKeepHistory()throws Exception {
        UUID id=upload("integration-"+UUID.randomUUID());String path="/api/v1/files/"+id;
        String coachToken=auth.token(coach,"COACH");
        var admin=request(path+"/versions","POST",auth.token(UUID.randomUUID(),"ADMIN"),null);assertThat(admin.statusCode()).isIn(403,415);
        assertThat(request("/actuator/prometheus","GET",null,null).statusCode()).isIn(401,403,404);
        assertThat(request("/actuator/prometheus","GET",auth.token(UUID.randomUUID(),"ADMIN"),null).statusCode()).isIn(403,404);
        assertThat(request(path,"GET",null,null).statusCode()).isEqualTo(401);
        String invalid=auth.sign(AuthTokenFixture.claims("42","ATHLETE").build(),"JWT");
        assertThat(request(path,"GET",invalid,null).statusCode()).isEqualTo(401);
        assertThat(request(path,"GET",auth.token(UUID.randomUUID(),"ATHLETE"),null).statusCode()).isEqualTo(403);
        assertThat(request(path,"PATCH",auth.token(athlete,"ATHLETE"),"{\"title\":\"forged\"}").statusCode()).isEqualTo(403);
        assertThat(request("/api/v1/files?athlete_id="+UUID.randomUUID(),"GET",auth.token(athlete,"ATHLETE"),null).statusCode()).isEqualTo(403);
        var download=request(path+"/download?version=1","GET",auth.token(athlete,"ATHLETE"),null);
        assertThat(download.statusCode()).isEqualTo(200);assertThat(download.body()).isEqualTo("original bytes".getBytes());
        assertThat(download.headers().firstValue("x-file-version")).contains("1");assertThat(download.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(download.headers().firstValue("content-disposition").orElseThrow()).startsWith("attachment").contains("filename*=");
        assertThat(request(path,"PATCH",auth.token(UUID.randomUUID(),"ADMIN"),"{\"description\":\"admin edit\"}").statusCode()).isEqualTo(200);
        assertThat(request(path,"DELETE",coachToken,null).statusCode()).isEqualTo(200);
        assertThat(request(path,"GET",auth.token(coach,"COACH"),null).statusCode()).isEqualTo(404);
        assertThat(request(path+"/download","GET",auth.token(UUID.randomUUID(),"ADMIN"),null).statusCode()).isEqualTo(404);
        assertThat(db.queryForObject("select count(*) from file_versions where file_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from files where file_id=? and deleted_at is not null",Integer.class,id)).isEqualTo(1);
    }
    @Test void concurrentVersionsAreMonotonicAndUnique()throws Exception {
        UUID id=upload("versions-"+UUID.randomUUID());
        try(var pool=Executors.newFixedThreadPool(4)) {
            var work=new ArrayList<Callable<Map<String,Object>>>();for(int i=0;i<4;i++)work.add(()->service.add(owner(),id,file("next bytes"),"parallel"));
            var versions=new HashSet<Integer>();for(var future:pool.invokeAll(work))versions.add((Integer)future.get().get("version"));
            assertThat(versions).containsExactlyInAnyOrder(2,3,4,5);
        }
        assertThat(db.queryForObject("select count(*) from file_versions where file_id=?",Integer.class,id)).isEqualTo(5);
        try(var stream=service.download(new Policy.Actor(athlete,Policy.Role.ATHLETE),id,1).stream()){assertThat(stream.readAllBytes()).isEqualTo("original bytes".getBytes());}
    }
    @Test void concurrentDuplicateIdentityHasOneWinner()throws Exception {
        activate(UUID.randomUUID(),1);String title="same-"+UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<Callable<Boolean>>();for(int i=0;i<4;i++)tasks.add(()->{
                try{service.upload(owner(),athlete,Policy.Category.OTHER,title,null,file("content"));return true;}
                catch(Api.Fault expected){assertThat(expected.status).isEqualTo(409);return false;}
            });
            int successes=0;for(var future:pool.invokeAll(tasks))if(future.get())successes++;assertThat(successes).isEqualTo(1);
        }
        assertThat(db.queryForObject("select count(*) from files where athlete_id=? and category='OTHER' and title=? and deleted_at is null",Integer.class,athlete,title)).isEqualTo(1);
    }
    @Test void duplicateConcurrentAndOutOfOrderEventsDoNotResurrectSubscription()throws Exception {
        UUID subscription=UUID.randomUUID();var active=SubscriptionConsumerTest.envelope(subscription,1,"SubscriptionActivated","ACTIVE");
        var cancelled=SubscriptionConsumerTest.envelope(subscription,3,"SubscriptionCancelled","CANCELLED");byte[] activeBytes=SubscriptionConsumerTest.bytes(active),cancelledBytes=SubscriptionConsumerTest.bytes(cancelled);
        try(var pool=Executors.newFixedThreadPool(4)){
            var results=pool.invokeAll(List.of(Executors.callable(()->subscriptions.apply(cancelledBytes)),Executors.callable(()->subscriptions.apply(activeBytes)),Executors.callable(()->subscriptions.apply(cancelledBytes)),Executors.callable(()->subscriptions.apply(activeBytes))));
            for(var future:results)future.get();
        }
        assertThat(db.queryForObject("select status from subscriptions where subscription_id=?",String.class,subscription)).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("select aggregate_version from subscriptions where subscription_id=?",Long.class,subscription)).isEqualTo(3);
        UUID another=UUID.randomUUID();activate(another,1);service.active(coach,athlete);
        assertThat(db.queryForObject("select count(*) from subscriptions where subscription_id in (?,?)",Integer.class,subscription,another)).isEqualTo(2);
        UUID eventId=UUID.fromString((String)cancelled.get("event_id"));assertThat(db.queryForObject("select count(*) from processed_events where consumer=? and event_id=?",Integer.class,SubscriptionConsumer.CONSUMER,eventId)).isEqualTo(1);
    }
    @Test void realKafkaProjectionAndMalformedDlqPreserveBytes()throws Exception {
        UUID subscription=UUID.randomUUID();byte[] active=SubscriptionConsumerTest.bytes(SubscriptionConsumerTest.envelope(subscription,1,"SubscriptionActivated","ACTIVE"));
        kafka.send("subscription.events",subscription.toString(),active).get(30,TimeUnit.SECONDS);
        await(()->db.queryForObject("select count(*) from subscriptions where subscription_id=?",Integer.class,subscription)==1);
        byte[] malformed={(byte)0xff,0,4};String key="malformed-"+UUID.randomUUID();
        Properties props=new Properties();props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,broker.getBootstrapServers());props.put(ConsumerConfig.GROUP_ID_CONFIG,"dlq-test-"+UUID.randomUUID());props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,"false");
        try(var consumer=new KafkaConsumer<String,byte[]>(props,new StringDeserializer(),new ByteArrayDeserializer())){
            consumer.subscribe(List.of("subscription.events.storage.dlq"));kafka.send("subscription.events",key,malformed).get(30,TimeUnit.SECONDS);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);ConsumerRecord<String,byte[]> found=null;
            while(System.nanoTime()<deadline&&found==null)for(var record:consumer.poll(Duration.ofMillis(500)))if(key.equals(record.key()))found=record;
            assertThat(found).isNotNull();assertThat(found.value()).isEqualTo(malformed);
            assertThat(found.headers().lastHeader("storage-source-offset")).isNotNull();
        }
    }
    @Test void malformedEventIsDlqedWithEvidenceAfterBoundedRetries()throws Exception {
        byte[] malformed={0,1,2};String key="evidence-"+UUID.randomUUID();
        kafka.send("subscription.events",key,malformed).get(30,TimeUnit.SECONDS);
        long started=System.nanoTime();Properties props=new Properties();props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,broker.getBootstrapServers());props.put(ConsumerConfig.GROUP_ID_CONFIG,"dlq-"+UUID.randomUUID());props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        try(var consumer=new KafkaConsumer<String,byte[]>(props,new StringDeserializer(),new ByteArrayDeserializer())){
            consumer.subscribe(List.of("subscription.events.storage.dlq"));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);ConsumerRecord<String,byte[]> found=null;
            while(System.nanoTime()<deadline&&found==null)for(var record:consumer.poll(Duration.ofMillis(500)))if(key.equals(record.key()))found=record;
            assertThat(found).isNotNull();assertThat(found.value()).isEqualTo(malformed);
            assertThat(found.headers().lastHeader("storage-error-code")).isNotNull();
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)).isLessThan(40);
        }
    }
    static void await(java.util.function.BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(100);assertThat(condition.getAsBoolean()).isTrue();
    }
}
