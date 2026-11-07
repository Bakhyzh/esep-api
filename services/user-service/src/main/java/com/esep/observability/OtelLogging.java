package com.esep.observability;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Component
@Profile("observability")
public class OtelLogging implements InitializingBean {
    private final OpenTelemetry telemetry;
    public OtelLogging(OpenTelemetry telemetry) { this.telemetry=telemetry; }
    @Override public void afterPropertiesSet() { OpenTelemetryAppender.install(telemetry); }
}
