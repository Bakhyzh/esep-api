package com.esep.observability;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.listener.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.LoggerFactory;
public final class KafkaRetryMetrics {
    private KafkaRetryMetrics(){}
    public static void attach(DefaultErrorHandler handler,MeterRegistry registry){
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.DEBUG);
        handler.setRetryListeners(new RetryListener(){
            @Override public void failedDelivery(ConsumerRecord<?,?> record,Exception error,int attempt){
                registry.counter("esep.kafka.processing.failures","topic",record.topic()).increment();
                LoggerFactory.getLogger(KafkaRetryMetrics.class).warn("Kafka processing failure topic={} attempt={} type={}",record.topic(),attempt,error.getClass().getSimpleName());
            }
            @Override public void recovered(ConsumerRecord<?,?> record,Exception error){
                registry.counter("esep.kafka.dead.letters","topic",record.topic()).increment();
                LoggerFactory.getLogger(KafkaRetryMetrics.class).warn("Kafka record moved to private DLT topic={}",record.topic());
            }
        });
    }
}
