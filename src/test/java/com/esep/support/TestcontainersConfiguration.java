package com.esep.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real infrastructure for integration tests, same images as docker-compose.
 * @ServiceConnection wires spring.datasource.*, spring.data.redis.* and spring.kafka.* automatically.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        // Avoid execve on a starter still open for copy in Docker Desktop (ETXTBSY).
        // Keep the real broker and original Kafka RUNNING readiness check.
        return new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"))
                .withCommand("sh", "-c", "while [ ! -s /tmp/testcontainers_start.sh ]; do sleep 0.1; done; exec bash /tmp/testcontainers_start.sh");
    }
}
