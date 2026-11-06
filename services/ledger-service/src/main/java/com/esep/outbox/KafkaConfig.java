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
    NewTopic transfersTopic(OutboxProperties p) {
        return TopicBuilder.name(p.topic()).partitions(3).replicas(1).build();
    }
}
