package com.esep.gateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import java.util.stream.Stream;
class ProductionSafetyTest {
    static final String[] VALID={"server.ssl.enabled=true","server.ssl.key-store=file:/run/secrets/service.p12","esep.gateway.issuer-uri=https://identity.example.invalid/realms/esep",
            "esep.gateway.jwk-set-uri=https://identity.example.invalid/certs",
            "esep.gateway.cors-origins=https://app.example.invalid",
            "esep.gateway.user-uri=https://user-uri.example.invalid",
            "esep.gateway.ledger-uri=https://ledger-uri.example.invalid",
            "esep.gateway.analytics-uri=https://analytics-uri.example.invalid",
            "esep.gateway.notification-uri=https://notification-uri.example.invalid",
            "spring.data.redis.ssl.enabled=true"};
    static MockEnvironment valid(){
        var e=new MockEnvironment();
        for(String property:VALID){int split=property.indexOf('=');e.setProperty(property.substring(0,split),property.substring(split+1));}
        e.setProperty("spring.data.redis.password","b".repeat(32));
        e.setProperty("server.ssl.key-store-password","t".repeat(32));
        return e;
    }
    @Test void acceptsExplicitSecureConfiguration(){ProductionSafety.validate(valid());}
    @ParameterizedTest @MethodSource("unsafe") void rejectsUnsafeConfiguration(String key,String value){
        var environment=valid().withProperty(key,value);
        assertThatThrownBy(()->ProductionSafety.validate(environment)).isInstanceOf(IllegalStateException.class);
    }
    static Stream<Arguments> unsafe(){return Stream.of(Arguments.of("server.ssl.enabled","false"),Arguments.of("esep.gateway.issuer-uri","http://identity.example.invalid"),
            Arguments.of("esep.gateway.cors-origins","*"),
            Arguments.of("esep.gateway.ledger-uri","http://ledger:8082"),
            Arguments.of("spring.data.redis.ssl.enabled","false"),
            Arguments.of("spring.data.redis.url","redis://insecure:6379"));}
    @Test void productionProfileFailsBeforeRuntimeBeans(){
        new ApplicationContextRunner().withUserConfiguration(ProductionSafety.class)
            .withPropertyValues("server.ssl.enabled=true","server.ssl.key-store=file:/run/secrets/service.p12","server.ssl.key-store-password="+"t".repeat(32),"spring.profiles.active=production","esep.gateway.issuer-uri=http://insecure.invalid")
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
