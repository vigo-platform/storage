package ir.vigoapp.storage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class InfrastructureConfiguration {
    @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager){return new TransactionTemplate(manager);}
    @Bean ConcurrentKafkaListenerContainerFactory<String,byte[]> kafkaListenerContainerFactory(
        ConsumerFactory<String,byte[]> consumers,KafkaTemplate<String,byte[]> kafka) {
        var factory=new ConcurrentKafkaListenerContainerFactory<String,byte[]>();factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        var errors=new DefaultErrorHandler((record,exception)->publishDlq(kafka,record),new FixedBackOff(1000L,3L));
        errors.setCommitRecovered(true);errors.setAckAfterHandle(true);
        errors.setResetStateOnRecoveryFailure(true);factory.setCommonErrorHandler(errors);
        return factory;
    }
    static void publishDlq(KafkaTemplate<String,byte[]> kafka,org.apache.kafka.clients.consumer.ConsumerRecord<?,?> record) {
        // Do not deserialize/re-serialize the payload. Headers and original binary evidence are retained.
        var headers=new RecordHeaders(record.headers().toArray());
        headers.add("storage-source-topic",record.topic().getBytes(StandardCharsets.UTF_8));
        headers.add("storage-source-partition",Integer.toString(record.partition()).getBytes(StandardCharsets.UTF_8));
        headers.add("storage-source-offset",Long.toString(record.offset()).getBytes(StandardCharsets.UTF_8));
        headers.add("storage-error-code","SUBSCRIPTION_EVENT_REJECTED".getBytes(StandardCharsets.UTF_8));
        var dlq=new ProducerRecord<String,byte[]>("subscription.events.storage.dlq",null,null,
            (String)record.key(),(byte[])record.value(),headers);
        try {kafka.send(dlq).get(65,TimeUnit.SECONDS);}
        catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new IllegalStateException("DLQ acknowledgment failed; offset must not advance",failure);
        }
    }
}
