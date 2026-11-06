package com.esep.security;

import com.esep.demo.DemoDataSeeder;
import com.esep.ratelimit.AuthRateLimitFilter;
import com.esep.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("local-keycloak")
class LocalKeycloakProfileTest extends IntegrationTest {
    @Autowired Environment environment;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void preparedProfileNeverIncludesDevSeedOrLegacyDecoder() {
        assertThat(environment.acceptsProfiles(Profiles.of("dev"))).isFalse();
        assertThat(environment.acceptsProfiles(Profiles.of("keycloak-integration"))).isTrue();
        assertThat(context.getBeansOfType(org.springframework.security.oauth2.jwt.JwtDecoder.class))
                .hasSize(1).containsKey("keycloakJwtDecoder");
        assertThat(context.getBeansOfType(DemoDataSeeder.class)).isEmpty();
        assertThat(context.getBeansOfType(AuthRateLimitFilter.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE script='R__dev_seed.sql'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email='admin@esep.dev'", Long.class)).isZero();
    }
}
