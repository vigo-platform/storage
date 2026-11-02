package ir.vigoapp.storage;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubscriptionConsumerTest {
    static final UUID COACH=UUID.randomUUID(),ATHLETE=UUID.randomUUID(),PLAN=UUID.randomUUID();
    static Map<String,Object> envelope(UUID subscription,long version,String type,String status) {
        var event=new LinkedHashMap<String,Object>();event.put("event_id",UUID.randomUUID().toString());event.put("event_type",type);event.put("event_version",1);
        event.put("occurred_at",Instant.now().toString());event.put("producer","order-service");event.put("aggregate_id",subscription.toString());event.put("aggregate_version",version);
        event.put("data",Map.of("subscription_id",subscription.toString(),"coach_id",COACH.toString(),"athlete_id",ATHLETE.toString(),"plan_id",PLAN.toString(),"status",status,"starts_at",Instant.now().minusSeconds(60).toString(),"ends_at",Instant.now().plusSeconds(3600).toString()));return event;
    }
    static byte[] bytes(Map<String,Object> event)throws Exception{return new ObjectMapper().writeValueAsBytes(event);}
    @Test void strictPositiveAggregateVersionRequired()throws Exception{
        var valid=envelope(UUID.randomUUID(),2,"SubscriptionActivated","ACTIVE");assertThat(SubscriptionConsumer.parse(bytes(valid)).version()).isEqualTo(2);
        for(Object version:List.of(0,-1,"2",1.2)) {valid.put("aggregate_version",version);byte[] bad=bytes(valid);assertThatThrownBy(()->SubscriptionConsumer.parse(bad)).isInstanceOf(IllegalArgumentException.class);}
        valid.remove("aggregate_version");byte[] bad=bytes(valid);assertThatThrownBy(()->SubscriptionConsumer.parse(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unknownTypesAndStatusMismatchAreRejectedForDlq()throws Exception{
        byte[] unknown=bytes(envelope(UUID.randomUUID(),1,"NewUnknownType","ACTIVE"));assertThatThrownBy(()->SubscriptionConsumer.parse(unknown)).isInstanceOf(IllegalArgumentException.class);
        byte[] wrong=bytes(envelope(UUID.randomUUID(),1,"SubscriptionExpired","ACTIVE"));assertThatThrownBy(()->SubscriptionConsumer.parse(wrong)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aggregateIdentityMustMatchSubscription()throws Exception{
        var event=envelope(UUID.randomUUID(),1,"SubscriptionActivated","ACTIVE");event.put("aggregate_id",UUID.randomUUID().toString());byte[] bad=bytes(event);
        assertThatThrownBy(()->SubscriptionConsumer.parse(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateJsonKeysAndNonJsonAreRejected(){
        assertThatThrownBy(()->SubscriptionConsumer.parse("{\"event_id\":\"a\",\"event_id\":\"b\"}".getBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SubscriptionConsumer.parse(new byte[]{(byte)0xff})).isInstanceOf(IllegalArgumentException.class);
    }
}
