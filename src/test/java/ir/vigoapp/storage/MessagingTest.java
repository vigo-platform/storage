package ir.vigoapp.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.*;

class MessagingTest {
    @SuppressWarnings("unchecked") KafkaTemplate<String,byte[]> kafka=mock(KafkaTemplate.class);
    @Test void dlqPreservesExactBytesAndEvidenceAndWaitsForAck(){
        byte[] payload={(byte)0xff,0,1};var record=new ConsumerRecord<String,byte[]>("subscription.events",2,42L,"pair",payload);
        record.headers().add("trace",new byte[]{7});
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        InfrastructureConfiguration.publishDlq(kafka,record);
        ArgumentCaptor<ProducerRecord<String,byte[]>> sent=ArgumentCaptor.forClass(ProducerRecord.class);verify(kafka).send(sent.capture());
        assertThat(sent.getValue().value()).isEqualTo(payload);assertThat(sent.getValue().topic()).isEqualTo("subscription.events.storage.dlq");
        assertThat(sent.getValue().headers().lastHeader("trace").value()).containsExactly(7);
        assertThat(new String(sent.getValue().headers().lastHeader("storage-source-offset").value())).isEqualTo("42");
    }
    @Test void dlqFailurePropagatesSoRecoveryCannotCommit(){
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        assertThatThrownBy(()->InfrastructureConfiguration.publishDlq(kafka,new ConsumerRecord<String,byte[]>("subscription.events",0,1,"key",new byte[]{1}))).isInstanceOf(IllegalStateException.class);
    }
    @Test void outboxMarksOnlyAfterAckAndRetriesWithUnchangedPayload(){
        var db=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call->((org.springframework.transaction.support.TransactionCallback<?>)call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        UUID aggregate=UUID.randomUUID();byte[] payload="stable-event-id".getBytes();
        when(db.queryForList(anyString())).thenReturn(List.of(Map.of("sequence",1L,"aggregate_id",aggregate,"payload",payload,"attempts",0)));
        when(kafka.send("storage.events",aggregate.toString(),payload)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        var outbox=new StorageOutbox(db,tx,kafka,new SimpleMeterRegistry());outbox.relayOnce();
        verify(db,never()).update(startsWith("update outbox set published_at"),anyLong());
        verify(db).update(startsWith("update outbox set attempts"),eq(1L),eq(1L));
        when(kafka.send("storage.events",aggregate.toString(),payload)).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        outbox.relayOnce();verify(db).update(startsWith("update outbox set published_at"),eq(1L));
        verify(kafka,times(2)).send("storage.events",aggregate.toString(),payload);
        ArgumentCaptor<String> query=ArgumentCaptor.forClass(String.class);verify(db,times(2)).queryForList(query.capture());
        assertThat(query.getValue()).contains("not exists","earlier.sequence<o.sequence","skip locked");
    }
    @Test void outboxRequiresMetadataTransaction(){
        var outbox=new StorageOutbox(mock(JdbcTemplate.class),mock(TransactionTemplate.class),kafka,new SimpleMeterRegistry());
        assertThatThrownBy(()->outbox.append(new StoredFile(UUID.randomUUID(),UUID.randomUUID(),"OTHER","title",null),"Uploaded",Map.of(),1)).isInstanceOf(IllegalStateException.class);
    }
}
