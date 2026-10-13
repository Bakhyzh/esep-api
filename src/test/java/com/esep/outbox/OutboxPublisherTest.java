package com.esep.outbox;

import com.esep.outbox.OutboxRepository.OutboxEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties("esep.transfers", Duration.ofSeconds(1), 10,
                Duration.ofSeconds(1), true);
        publisher = new OutboxPublisher(repository, kafkaTemplate, properties, transactionTemplate);
    }

    @Test
    void allSent_allMarkedPublished() {
        when(repository.lockNextBatch(10)).thenReturn(List.of(event(1), event(2)));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(acked());

        assertThat(publisher.publishBatch()).isEqualTo(2);

        verify(repository).markPublished(List.of(1L, 2L));
    }

    @Test
    void brokerFails_stopsAtFirstFailureToKeepOrder() {
        when(repository.lockNextBatch(10)).thenReturn(List.of(event(1), event(2), event(3)));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(acked())
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(publisher.publishBatch()).isEqualTo(1);

        verify(repository).markPublished(List.of(1L));
        verify(repository).markFailed(eq(2L), anyString());
        verify(repository, never()).markFailed(eq(3L), anyString()); // event 3 was not even tried
    }

    @Test
    void interruptedWhileWaitingForAck_keepsTheInterruptFlag() {
        when(repository.lockNextBatch(10)).thenReturn(List.of(event(1)));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());   // never acked

        Thread.currentThread().interrupt();
        int published = publisher.publishBatch();

        assertThat(Thread.interrupted()).isTrue();   // also clears it for the next tests
        assertThat(published).isZero();
        verify(repository).markFailed(eq(1L), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void record_carriesKeyPayloadAndEventHeaders() {
        OutboxEvent event = new OutboxEvent(1, UUID.randomUUID(), TransferCompletedEvent.TYPE, "10", "{\"a\":1}");
        when(repository.lockNextBatch(10)).thenReturn(List.of(event));
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        when(kafkaTemplate.send(sent.capture())).thenReturn(acked());

        publisher.publishBatch();

        ProducerRecord<String, String> record = sent.getValue();
        assertThat(record.topic()).isEqualTo("esep.transfers");
        assertThat(record.key()).isEqualTo("10");   // sender account: per-account ordering
        assertThat(record.value()).isEqualTo("{\"a\":1}");
        assertThat(new String(record.headers().lastHeader(OutboxPublisher.EVENT_ID_HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo(event.eventId().toString());
        assertThat(new String(record.headers().lastHeader(OutboxPublisher.EVENT_TYPE_HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("TRANSFER_COMPLETED");
    }

    @Test
    void emptyOutbox_doesNothing() {
        when(repository.lockNextBatch(10)).thenReturn(List.of());

        assertThat(publisher.publishBatch()).isZero();

        verify(repository, never()).markPublished(anyList());
    }

    private static OutboxEvent event(long id) {
        return new OutboxEvent(id, UUID.randomUUID(), TransferCompletedEvent.TYPE, "10", "{}");
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture((SendResult<String, String>) org.mockito.Mockito.mock(SendResult.class));
    }
}
