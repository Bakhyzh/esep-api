package com.esep.observability;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.*;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class TraceSupportTest {
    @Test void restoresRealW3cParentOnAnotherThreadAndClosesScopeAfterFailure() throws Exception {
        try(var sdk=SdkTracerProvider.builder().build();var executor=Executors.newSingleThreadExecutor()){
            var otel=sdk.get("durable-outbox-test");
            Tracer tracer=new OtelTracer(otel,new OtelCurrentTraceContext(),event->{});
            Propagator propagator=new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()),otel);
            var beans=new DefaultListableBeanFactory();beans.registerSingleton("tracer",tracer);beans.registerSingleton("propagator",propagator);
            var support=new TraceSupport(beans.getBeanProvider(Tracer.class),beans.getBeanProvider(Propagator.class));
            assertThat(support.capture()).isNull();
            var request=tracer.nextSpan().name("request").start();String parent;
            try(var scope=tracer.withSpan(request)){parent=support.capture();}
            finally{request.end();}
            assertThat(parent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
            String traceId=parent.split("-")[1];
            executor.submit(()->{
                assertThat(tracer.currentSpan()).isNull();
                assertThatThrownBy(()->support.publish(parent,()->{
                    assertThat(tracer.currentSpan().context().traceId()).isEqualTo(traceId);
                    assertThat(tracer.currentSpan().context().spanId()).isNotEqualTo(parent.split("-")[2]);
                    throw new IllegalStateException("simulated transport failure");
                })).isInstanceOf(IllegalStateException.class);
                assertThat(tracer.currentSpan()).isNull();return null;
            }).get(5,TimeUnit.SECONDS);
            support.publish("untrusted arbitrary text",()->assertThat(tracer.currentSpan()).isNull());
        }
    }
}
