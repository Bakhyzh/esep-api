package com.esep.gateway;

import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** Redis decides before forwarding. Downstream errors are never retried by this filter. */
@Component
public class DistributedRateLimit implements GlobalFilter, Ordered {
    private static final RedisScript<Long> SCRIPT=RedisScript.of("""
        local n=redis.call('INCR',KEYS[1])
        if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end
        return n
        """,Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final GatewayProperties properties;
    public DistributedRateLimit(ReactiveStringRedisTemplate redis, GatewayProperties properties) {
        this.redis=redis; this.properties=properties;
    }
    @Override public int getOrder() { return -100; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean read=HttpMethod.GET.equals(exchange.getRequest().getMethod()) || HttpMethod.HEAD.equals(exchange.getRequest().getMethod());
        return exchange.getPrincipal().cast(JwtAuthenticationToken.class).flatMap(authentication->{
            Route route=exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            String identity=authentication.getToken().getIssuer()+"\u0000"+authentication.getToken().getSubject();
            String key="esep:gateway:v1:"+hash(identity)+":"+route.getId()+":"+(read?"read":"write");
            int limit=read?properties.readLimit():properties.writeLimit();
            // Error handling is restricted to the Redis decision, before any downstream request.
            return redis.execute(SCRIPT,List.of(key),List.of(String.valueOf(properties.windowSeconds()*1000L)))
                    .next().timeout(Duration.ofMillis(500)).map(n->n<=limit?Decision.ALLOW:Decision.LIMITED)
                    .defaultIfEmpty(Decision.UNAVAILABLE).onErrorReturn(read?Decision.ALLOW:Decision.UNAVAILABLE)
                    .flatMap(decision->switch(decision) {
                        case ALLOW -> chain.filter(exchange);
                        case LIMITED -> problem(exchange,429,"RATE_LIMITED","Request limit reached",properties.windowSeconds());
                        case UNAVAILABLE -> problem(exchange,503,"DEPENDENCY_UNAVAILABLE","Request protection temporarily unavailable",1);
                    });
        });
    }
    private enum Decision { ALLOW,LIMITED,UNAVAILABLE }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Mono<Void> problem(ServerWebExchange exchange,int status,String code,String title,int retry) {
        var response=exchange.getResponse(); response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set("Retry-After",String.valueOf(retry));
        byte[] body=("{\"status\":"+status+",\"code\":\""+code+"\",\"title\":\""+title+"\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
