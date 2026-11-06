package com.esep.gateway;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.util.*;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter implements WebFilter {
    private final GatewayProperties properties;
    public CorrelationFilter(GatewayProperties properties) { this.properties=properties; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String supplied=exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
        String id=supplied!=null && supplied.matches("[A-Za-z0-9_-]{1,64}") ? supplied : UUID.randomUUID().toString();
        var clean=exchange.mutate().request(r->r.headers(h->{
            for(String name:List.copyOf(h.headerNames())) {
                String lower=name.toLowerCase(Locale.ROOT);
                if(lower.startsWith("x-user-") || lower.startsWith("x-sc-lb-") || lower.startsWith("x-forwarded-") || lower.equals("forwarded")) h.remove(name);
            }
            h.set("X-Correlation-ID",id);
        })).build();
        clean.getResponse().getHeaders().set("X-Correlation-ID",id);
        clean.getResponse().getHeaders().set("X-Gateway-Instance",properties.instance());
        return chain.filter(clean);
    }
}
