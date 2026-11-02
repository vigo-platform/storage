package ir.vigoapp.storage;

import com.fasterxml.jackson.databind.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SubscriptionConsumer {
    static final String CONSUMER="storage-subscriptions-v1";
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final JdbcTemplate db;private final TransactionTemplate tx;
    SubscriptionConsumer(JdbcTemplate db,TransactionTemplate tx){this.db=db;this.tx=tx;}
    @KafkaListener(topics="subscription.events",groupId=CONSUMER)
    void consume(ConsumerRecord<String,byte[]> record,Acknowledgment acknowledgment) {
        Event parsed=parse(record.value());
        if(!parsed.subscription().toString().equals(record.key()))throw new IllegalArgumentException("Subscription key must equal aggregate_id");
        apply(parsed);
        // apply() returns only after the Postgres commit; Kafka never owns this transaction.
        acknowledgment.acknowledge();
    }
    void apply(byte[] payload) { apply(parse(payload)); }
    private void apply(Event event) {
        tx.executeWithoutResult(status->{
            if(db.update("insert into processed_events(consumer,event_id) values(?,?) on conflict do nothing",CONSUMER,event.eventId)==0)return;
            // One row per subscription, never one row per pair. Version comparison is atomic under the upsert lock.
            db.update("""
                insert into subscriptions(subscription_id,coach_id,athlete_id,plan_id,status,starts_at,ends_at,aggregate_version,updated_at)
                values(?,?,?,?,?,?,?,?,current_timestamp)
                on conflict(subscription_id) do update set status=excluded.status,starts_at=excluded.starts_at,ends_at=excluded.ends_at,
                aggregate_version=excluded.aggregate_version,updated_at=current_timestamp
                where subscriptions.aggregate_version<excluded.aggregate_version
                  and subscriptions.coach_id=excluded.coach_id and subscriptions.athlete_id=excluded.athlete_id
                  and subscriptions.plan_id=excluded.plan_id
                """,event.subscription,event.coach,event.athlete,event.plan,event.status,Timestamp.from(event.starts),Timestamp.from(event.ends),event.version);
            var identity=db.queryForMap("select coach_id,athlete_id,plan_id from subscriptions where subscription_id=?",event.subscription);
            if(!event.coach.equals(identity.get("coach_id"))||!event.athlete.equals(identity.get("athlete_id"))||!event.plan.equals(identity.get("plan_id")))throw new IllegalArgumentException("Subscription identity changed");
        });
    }
    record Event(UUID eventId,UUID subscription,UUID coach,UUID athlete,UUID plan,String status,Instant starts,Instant ends,long version) {}
    static Event parse(byte[] payload) {
        try {
            if(payload==null||payload.length>256*1024)throw new IllegalArgumentException();
            JsonNode root=JSON.readTree(payload);if(!root.isObject())throw new IllegalArgumentException();
            UUID event=uuid(root,"event_id"),aggregate=uuid(root,"aggregate_id");
            if(!root.path("event_version").isIntegralNumber()||!root.path("event_version").canConvertToInt()||root.path("event_version").intValue()!=1)throw new IllegalArgumentException();
            var version=root.path("aggregate_version");if(!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<=0)throw new IllegalArgumentException();
            Instant.parse(text(root,"occurred_at"));if(!text(root,"producer").equals("order-service"))throw new IllegalArgumentException();
            String status=switch(text(root,"event_type")) {
                case "SubscriptionActivated" -> "ACTIVE";case "SubscriptionExpired" -> "EXPIRED";case "SubscriptionCancelled" -> "CANCELLED";
                default -> throw new IllegalArgumentException("Unknown event type");
            };
            JsonNode data=root.path("data");UUID subscription=uuid(data,"subscription_id"),coach=uuid(data,"coach_id"),athlete=uuid(data,"athlete_id"),plan=uuid(data,"plan_id");
            Instant starts=Instant.parse(text(data,"starts_at")),ends=Instant.parse(text(data,"ends_at"));
            if(!aggregate.equals(subscription)||coach.equals(athlete)||!status.equals(text(data,"status"))||!ends.isAfter(starts))throw new IllegalArgumentException();
            return new Event(event,subscription,coach,athlete,plan,status,starts,ends,version.longValue());
        }catch(Exception e){throw new IllegalArgumentException("Invalid subscription event",e);}
    }
    private static String text(JsonNode node,String key){var value=node.path(key);if(!value.isTextual())throw new IllegalArgumentException();return value.textValue();}
    private static UUID uuid(JsonNode node,String key){String value=text(node,key);UUID id=UUID.fromString(value);if(!id.toString().equalsIgnoreCase(value))throw new IllegalArgumentException();return id;}
}
