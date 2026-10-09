package com.esep.outbox;

import com.esep.notification.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class KafkaConfig {

    static final int PARTITIONS = 3;

    // created on startup by KafkaAdmin if missing; the DLT needs at least as many partitions,
    // because a failed record goes to the same partition number of the DLT
    @Bean
    NewTopic transfersTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(PARTITIONS).replicas(1).build();
    }

    @Bean
    NewTopic transfersDeadLetterTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic()).partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * Consumer errors: retry a few times with a pause (transient problems: DB hiccup, deadlock),
     * then publish the record to "<topic>.DLT" with the exception in headers and move on,
     * so one poison message does not block the partition forever.
     * Invalid payloads are not retried at all: they will never succeed.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                          @Value("${esep.kafka.retry.interval}") Duration interval,
                                          @Value("${esep.kafka.retry.max-attempts}") long maxAttempts) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(interval.toMillis(), maxAttempts));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
