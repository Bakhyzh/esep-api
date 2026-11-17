package com.esep.security;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import java.util.stream.Stream;
class ProductionSafetyTest {
    static final String[] VALID={"server.ssl.enabled=true","server.ssl.key-store=file:/run/secrets/service.p12","esep.identity.enabled=true","esep.identity.jdbc-url=jdbc:postgresql://db.example.invalid/service?sslmode=verify-full","esep.identity.username=service_projector","esep.analytics.ledger-base-url=https://ledger.example.invalid","esep.security.keycloak.issuer-uri=https://identity.example.invalid/realms/esep",
            "esep.security.keycloak.jwk-set-uri=https://identity.example.invalid/certs",
            "esep.cors.allowed-origins=https://app.example.invalid",
            "spring.datasource.url=jdbc:postgresql://db.example.invalid/service?sslmode=verify-full",
            "spring.datasource.username=service_runtime",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.kafka.properties.security.protocol=SASL_SSL",
            "spring.kafka.properties.sasl.jaas.config=external-jaas",
            "spring.kafka.properties.ssl.endpoint.identification.algorithm=https",
            "spring.data.redis.ssl.enabled=true"};
    static MockEnvironment valid(){
        var e=new MockEnvironment();
        for(String property:VALID){int split=property.indexOf('=');e.setProperty(property.substring(0,split),property.substring(split+1));}
        e.setProperty("spring.datasource.password","a".repeat(32));
        e.setProperty("spring.data.redis.password","b".repeat(32));
        e.setProperty("esep.identity.password","c".repeat(32));
        e.setProperty("server.ssl.key-store-password","t".repeat(32));
        return e;
    }
    @Test void acceptsExplicitSecureConfiguration(){ProductionSafety.validate(valid());}
    @ParameterizedTest @MethodSource("unsafe") void rejectsUnsafeConfiguration(String key,String value){
        var environment=valid().withProperty(key,value);
        assertThatThrownBy(()->ProductionSafety.validate(environment)).isInstanceOf(IllegalStateException.class);
    }
    static Stream<Arguments> unsafe(){return Stream.of(Arguments.of("server.ssl.enabled","false"),Arguments.of("esep.identity.enabled","false"),Arguments.of("esep.identity.jdbc-url","jdbc:postgresql://db.example.invalid/user_db?sslmode=verify-full"),Arguments.of("esep.identity.username","service_runtime"),Arguments.of("esep.security.keycloak.issuer-uri","http://identity.example.invalid"),
            Arguments.of("esep.cors.allowed-origins","*"),
            Arguments.of("spring.datasource.url","jdbc:postgresql://db.example.invalid/service"),
            Arguments.of("spring.datasource.url","jdbc:postgresql://db/service?sslmode=disable&sslmode=verify-full"),
            Arguments.of("spring.datasource.url","jdbc:postgresql://db/service?sslmode=verify-full&sslfactory=insecure"),
            Arguments.of("spring.datasource.username","service_migrator"),
            Arguments.of("spring.flyway.enabled","true"),
            Arguments.of("spring.jpa.hibernate.ddl-auto","update"),
            Arguments.of("spring.kafka.properties.security.protocol","PLAINTEXT"),
            Arguments.of("spring.kafka.properties.ssl.endpoint.identification.algorithm",""),
            Arguments.of("spring.data.redis.ssl.enabled","false"),
            Arguments.of("spring.data.redis.url","redis://insecure:6379"));}
    @Test void productionProfileFailsBeforeRuntimeBeans(){
        new ApplicationContextRunner().withUserConfiguration(ProductionSafety.class)
            .withPropertyValues("server.ssl.enabled=true","server.ssl.key-store=file:/run/secrets/service.p12","server.ssl.key-store-password="+"t".repeat(32),"spring.profiles.active=production","esep.security.keycloak.issuer-uri=http://insecure.invalid")
            .run(context->{assertThat(context).hasFailed();assertThat(context.getStartupFailure()).hasStackTraceContaining("Production HTTPS endpoint required");});
    }
    @Test void secureProductionContextStarts(){
        new ApplicationContextRunner().withUserConfiguration(ProductionSafety.class).withPropertyValues(VALID)
            .withPropertyValues("spring.profiles.active=production","server.ssl.key-store-password="+"t".repeat(32),"spring.datasource.password="+"a".repeat(32),
                "spring.data.redis.password="+"b".repeat(32),"esep.identity.password="+"c".repeat(32))
            .run(context->assertThat(context).hasNotFailed().hasBean("enforceProductionSafety"));
    }
    @Test void developmentDoesNotActivateProductionGuard(){
        new ApplicationContextRunner().withUserConfiguration(ProductionSafety.class)
            .run(context->assertThat(context).doesNotHaveBean("enforceProductionSafety"));
    }
}
