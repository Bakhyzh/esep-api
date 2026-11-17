package com.esep.security;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

/** Native ARM/amd64 compression security backports must remain interoperable with the real broker. */
@Testcontainers
class KafkaCompressionTest {
    @Container static final KafkaContainer KAFKA=new KafkaContainer(DockerImageName.parse(
        "apache/kafka:3.9.1@sha256:4ceccc577f03f51f6af8dbfda55194d0d892f4fa7913ffbded567ce3895622ed").asCompatibleSubstituteFor("apache/kafka"))
        .withCommand("sh","-c","while [ ! -s /tmp/testcontainers_start.sh ]; do sleep 0.1; done; exec bash /tmp/testcontainers_start.sh");
    @ParameterizedTest @ValueSource(strings={"lz4","snappy"})
    void patchedCodecRoundTripsExactImmutablePayload(String codec) throws Exception {
        String topic="security-codec-"+UUID.randomUUID();
        try(var admin=AdminClient.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))){
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(15,TimeUnit.SECONDS);
        }
        Properties producer=new Properties();producer.put("bootstrap.servers",KAFKA.getBootstrapServers());
        producer.put("key.serializer",StringSerializer.class);producer.put("value.serializer",StringSerializer.class);
        producer.put("compression.type",codec);producer.put("acks","all");producer.put("max.block.ms",5000);
        String payload="{\"amount\":\"0.0001\",\"eventId\":\""+UUID.randomUUID()+"\",\"synthetic\":\""+"codec-fixture-".repeat(10000)+"\"}";
        try(var client=new KafkaProducer<String,String>(producer)){
            client.send(new ProducerRecord<>(topic,"immutable-fact",payload)).get(15,TimeUnit.SECONDS);
        }
        Properties consumer=new Properties();consumer.put("bootstrap.servers",KAFKA.getBootstrapServers());
        consumer.put("key.deserializer",StringDeserializer.class);consumer.put("value.deserializer",StringDeserializer.class);
        consumer.put("group.id","codec-"+UUID.randomUUID());consumer.put("auto.offset.reset","earliest");consumer.put("enable.auto.commit",false);
        try(var client=new KafkaConsumer<String,String>(consumer)){
            client.subscribe(List.of(topic));long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            List<ConsumerRecord<String,String>> received=new ArrayList<>();
            while(received.isEmpty() && System.nanoTime()<deadline)client.poll(Duration.ofMillis(200)).forEach(received::add);
            assertThat(received).hasSize(1);assertThat(received.getFirst().key()).isEqualTo("immutable-fact");
            assertThat(received.getFirst().value()).isEqualTo(payload);
        }
    }
}
