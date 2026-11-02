package ir.vigoapp.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class StorageOutbox {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate db;private final TransactionTemplate tx;private final KafkaTemplate<String,byte[]> kafka;
    private final MeterRegistry meters;private final AtomicLong pending=new AtomicLong(),oldest=new AtomicLong(),retries=new AtomicLong();
    StorageOutbox(JdbcTemplate db,TransactionTemplate tx,KafkaTemplate<String,byte[]> kafka,MeterRegistry meters) {
        this.db=db;this.tx=tx;this.kafka=kafka;this.meters=meters;
        meters.gauge("storage.outbox.pending",pending);meters.gauge("storage.outbox.oldest.seconds",oldest);meters.gauge("storage.outbox.retries",retries);
    }
    void append(StoredFile file,String type,Map<String,Object> data,long aggregateVersion) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Outbox requires metadata transaction");
        UUID eventId=UUID.randomUUID();
        var event=new LinkedHashMap<String,Object>();event.put("event_id",eventId.toString());event.put("event_type",type);
        event.put("event_version",1);event.put("occurred_at",Instant.now().toString());event.put("producer","storage-service");
        event.put("aggregate_id",file.id.toString());event.put("aggregate_version",aggregateVersion);event.put("data",data);
        try {
            db.update("insert into outbox(event_id,aggregate_id,aggregate_version,event_type,payload) values(?,?,?,?,?)",eventId,file.id,aggregateVersion,type,JSON.writeValueAsBytes(event));
        } catch(java.io.IOException e){throw new IllegalStateException("Event serialization failed",e);}
    }
    @Scheduled(fixedDelayString="${storage.outbox-delay-ms:1000}")
    void relay() {
        try {relayOnce();refreshMetrics();}
        catch(Exception e){meters.counter("storage.outbox.relay.failures").increment();org.slf4j.LoggerFactory.getLogger(StorageOutbox.class).warn("Outbox relay deferred ({})",e.getClass().getSimpleName());}
    }
    boolean relayOnce() {
        return Boolean.TRUE.equals(tx.execute(status->{
            var rows=db.queryForList("""
                select o.sequence,o.aggregate_id,o.payload,o.attempts from outbox o
                where o.published_at is null and o.next_attempt_at<=current_timestamp
                  and not exists(select 1 from outbox earlier where earlier.aggregate_id=o.aggregate_id
                    and earlier.sequence<o.sequence and earlier.published_at is null)
                order by o.sequence limit 1 for update of o skip locked
                """);
            if(rows.isEmpty())return false;
            var row=rows.getFirst();long sequence=((Number)row.get("sequence")).longValue();
            try {
                // Keep the row lock through broker acknowledgment. Other workers cannot publish a successor.
                kafka.send("storage.events",row.get("aggregate_id").toString(),(byte[])row.get("payload")).get(65,TimeUnit.SECONDS);
                db.update("update outbox set published_at=current_timestamp where sequence=?",sequence);
                meters.counter("storage.outbox.published").increment();
            } catch(Exception e) {
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                int attempts=((Number)row.get("attempts")).intValue();long delay=Math.min(300,1L<<Math.min(attempts,8));
                db.update("update outbox set attempts=attempts+1,next_attempt_at=current_timestamp+(? * interval '1 second') where sequence=?",delay,sequence);
                meters.counter("storage.outbox.publish.failures").increment();
            }
            return true;
        }));
    }
    private void refreshMetrics(){
        var stats=db.queryForMap("select count(*) as pending,coalesce(extract(epoch from current_timestamp-min(created_at)),0)::bigint as oldest,coalesce(sum(attempts),0)::bigint as retries from outbox where published_at is null");
        pending.set(((Number)stats.get("pending")).longValue());oldest.set(((Number)stats.get("oldest")).longValue());retries.set(((Number)stats.get("retries")).longValue());
    }
}
