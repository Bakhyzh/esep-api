package com.esep.support;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;

/**
 * Rate limit and demo seed switched on (both are off in the "test" profile).
 * One shared base class, so all subclasses reuse one cached Spring context and one set of containers.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "esep.rate-limit.enabled=true",
        "esep.rate-limit.max-requests=3",
        "esep.rate-limit.window=1m",
        "esep.demo.enabled=true",
        "esep.demo.email=Demo@Esep.dev",
        "esep.demo.password=demo-password-123",
        "esep.demo.initial-balance=5000"
})
public abstract class ProdFeaturesIntegrationTest extends IntegrationTest {

    public static final String DEMO_EMAIL = "demo@esep.dev";
    public static final String DEMO_PASSWORD = "demo-password-123";
}
