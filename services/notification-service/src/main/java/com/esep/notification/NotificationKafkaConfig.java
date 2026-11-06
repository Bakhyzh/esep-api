package com.esep.notification;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class NotificationKafkaConfig {
    @Bean org.springframework.kafka.support.ProducerListener<Object,Object> safeProducerListener(){
        return new org.springframework.kafka.support.ProducerListener<>(){
            @Override public void onError(org.apache.kafka.clients.producer.ProducerRecord<Object,Object> record,
                    org.apache.kafka.clients.producer.RecordMetadata metadata,Exception error){
                org.slf4j.LoggerFactory.getLogger(NotificationKafkaConfig.class).warn("Kafka send failed: {}",error.getClass().getSimpleName());
            }
        };
    }
    @Bean org.springframework.kafka.core.KafkaAdmin.NewTopics notificationTopics(){
        java.util.List<NewTopic> topics=new java.util.ArrayList<>();
        for(String topic:java.util.List.of("esep.users.v1","esep.ledger.transfers.v1","esep.notifications.replay.v1")){
            topics.add(TopicBuilder.name(topic).partitions(3).replicas(1).build());
            topics.add(TopicBuilder.name(topic+".notification.DLT").partitions(3).replicas(1).build());
        }
        return new org.springframework.kafka.core.KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
    @Bean DefaultErrorHandler notificationErrors(KafkaTemplate<String,String> kafka){
        var recoverer=new DeadLetterPublishingRecoverer(kafka,(record,error)->new TopicPartition(record.topic()+".notification.DLT",record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var handler=new DefaultErrorHandler(recoverer,new FixedBackOff(500,3));
        handler.addNotRetryableExceptions(IllegalArgumentException.class,DataIntegrityViolationException.class);
        return handler;
    }
}
