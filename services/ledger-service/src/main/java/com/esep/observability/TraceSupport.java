package com.esep.observability;
import io.micrometer.tracing.*;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;
/** Durable trace context only. No baggage, principal, request body or credentials are stored. */
@Component
public class TraceSupport {
    private final ObjectProvider<Tracer> tracers;
    private final ObjectProvider<Propagator> propagators;
    public TraceSupport(ObjectProvider<Tracer> tracers,ObjectProvider<Propagator> propagators){this.tracers=tracers;this.propagators=propagators;}
    public String capture(){
        var tracer=tracers.getIfAvailable();var propagator=propagators.getIfAvailable();
        if(tracer==null || propagator==null || tracer.currentSpan()==null)return null;
        Map<String,String> headers=new HashMap<>();propagator.inject(tracer.currentSpan().context(),headers,Map::put);
        String parent=headers.get("traceparent");return valid(parent)?parent:null;
    }
    private boolean valid(String value){return value!=null && value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");}
    @FunctionalInterface public interface Action {void run()throws Exception;}
    public void publish(String parent,Action action)throws Exception {
        var tracer=tracers.getIfAvailable();var propagator=propagators.getIfAvailable();
        if(tracer==null || propagator==null || !valid(parent)){action.run();return;}
        var span=propagator.extract(Map.of("traceparent",parent),Map<String,String>::get).name("outbox.publish").start();
        try(var scope=tracer.withSpan(span)){action.run();}
        catch(Exception error){span.tag("error.type",error.getClass().getSimpleName());throw error;}
        finally{span.end();}
    }
}
