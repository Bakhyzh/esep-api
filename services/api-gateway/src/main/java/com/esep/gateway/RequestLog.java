package com.esep.gateway;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;
@Component
public class RequestLog implements GlobalFilter,Ordered {
    @Override public int getOrder(){return -300;}
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain){
        return chain.filter(exchange).doFinally(signal->{
            Route route=exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            LoggerFactory.getLogger(RequestLog.class).atInfo().addKeyValue("route",route==null?"unknown":route.getId())
                .addKeyValue("status",exchange.getResponse().getStatusCode()==null?(signal==reactor.core.publisher.SignalType.ON_ERROR?500:signal==reactor.core.publisher.SignalType.CANCEL?499:200):exchange.getResponse().getStatusCode().value())
                .addKeyValue("correlation_id",exchange.getRequest().getHeaders().getFirst("X-Correlation-ID")).log("Gateway request completed");
        });
    }
}
