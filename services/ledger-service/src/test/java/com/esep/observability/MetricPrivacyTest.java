package com.esep.observability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class MetricPrivacyTest {
    @Test void remoteInputCannotCreateUserOrArbitraryUrlLabels(){
        var registry=new SimpleMeterRegistry();
        try{
            registry.config().meterFilter(new MetricPrivacy().safeMetricTags());
            var meter=registry.counter("http.client", "uri","https://example.invalid/api/accounts/12345?token=sentinel", "user","private-email", "authorization","Bearer sentinel");
            assertThat(meter.getId().getTag("uri")).isEqualTo("/api/accounts/{id}");
            assertThat(meter.getId().getTag("user")).isNull();
            assertThat(meter.getId().getTag("authorization")).isNull();
            var unknown=registry.counter("http.server","uri","/unmapped/private-email");
            assertThat(unknown.getId().getTag("uri")).isEqualTo("other");
        }finally{registry.close();}
    }
}
