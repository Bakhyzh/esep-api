package com.esep.outbox;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class KafkaConfig {
    @Bean
    org.springframework.kafka.support.ProducerListener<Object,Object> safeProducerListener() {
        return new org.springframework.kafka.support.ProducerListener<>() {
            @Override public void onError(org.apache.kafka.clients.producer.ProducerRecord<Object,Object> record,
                    org.apache.kafka.clients.producer.RecordMetadata metadata,Exception error) {
                org.slf4j.LoggerFactory.getLogger(KafkaConfig.class).warn("Kafka send failed: {}",error.getClass().getSimpleName());
            }
        };
    }
    @Bean
    org.springframework.kafka.core.KafkaAdmin.NewTopics factTopics(){return new org.springframework.kafka.core.KafkaAdmin.NewTopics(
        TopicBuilder.name("esep.ledger.postings.v1").partitions(3).replicas(1).build(),
        TopicBuilder.name("esep.ledger.accounts.v1").partitions(3).replicas(1).build());}
    @Bean
    NewTopic transfersTopic(OutboxProperties p) {
        return TopicBuilder.name(p.topic()).partitions(3).replicas(1).build();
    }
}
