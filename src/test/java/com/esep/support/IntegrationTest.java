package com.esep.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for tests against a real PostgreSQL in Docker.
 * The Spring context (and so the container) is cached and shared by all subclasses.
 * Profile "test" instead of the default "dev": no dev seed data in tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public abstract class IntegrationTest {
}
