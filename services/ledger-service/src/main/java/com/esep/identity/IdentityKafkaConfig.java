package com.esep.identity;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name="esep.identity.enabled",havingValue="true")
public class IdentityKafkaConfig {
    @Bean NewTopic identityTopic(@Value("${esep.identity.topic:esep.users.v1}") String topic) {return TopicBuilder.name(topic).partitions(3).replicas(1).build();}
    @Bean NewTopic identityDlt(@Value("${esep.identity.topic:esep.users.v1}") String topic) {return TopicBuilder.name(topic+".ledger.DLT").partitions(3).replicas(1).build();}
    @Bean DefaultErrorHandler identityErrors(KafkaTemplate<String,String> kafka) {
        var recoverer=new DeadLetterPublishingRecoverer(kafka,(record,error)->new TopicPartition(record.topic()+".ledger.DLT",record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var errors=new DefaultErrorHandler(recoverer,new FixedBackOff(500,3));
        errors.addNotRetryableExceptions(IllegalArgumentException.class,DataIntegrityViolationException.class);
        return errors;
    }
}
