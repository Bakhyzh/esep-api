package com.esep.gateway;

import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.*;
import java.nio.charset.StandardCharsets;

/** Bound even chunked requests before contacting a business service. No payload logging. */
@Component
public class RequestBodyLimit implements GlobalFilter, Ordered {
    private static final int MAX_BYTES=64*1024;
    private record Payload(byte[] bytes,boolean tooLarge) { }
    @Override public int getOrder() { return -200; }
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain) {
        if(exchange.getRequest().getHeaders().getContentLength()>MAX_BYTES)return reject(exchange);
        // Handle aggregation errors before the downstream chain, never reinterpret committed business errors.
        return DataBufferUtils.join(exchange.getRequest().getBody(),MAX_BYTES).map(buffer->{
            try { byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);return new Payload(bytes,false); }
            finally { DataBufferUtils.release(buffer); }
        }).defaultIfEmpty(new Payload(new byte[0],false))
          .onErrorResume(DataBufferLimitException.class,error->Mono.just(new Payload(new byte[0],true)))
          .flatMap(payload->{
              if(payload.tooLarge())return reject(exchange);
              var request=new ServerHttpRequestDecorator(exchange.getRequest()) {
                  @Override public HttpHeaders getHeaders() {
                      var headers=new HttpHeaders();headers.putAll(super.getHeaders());
                      headers.remove(HttpHeaders.TRANSFER_ENCODING);headers.setContentLength(payload.bytes().length);
                      return HttpHeaders.readOnlyHttpHeaders(headers);
                  }
                  @Override public Flux<DataBuffer> getBody() {
                      return payload.bytes().length==0?Flux.empty():Flux.defer(()->Mono.just(exchange.getResponse().bufferFactory().wrap(payload.bytes())));
                  }
              };
              return chain.filter(exchange.mutate().request(request).build());
          });
    }
    private Mono<Void> reject(ServerWebExchange exchange) {
        var response=exchange.getResponse();response.setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body="{\"status\":413,\"code\":\"REQUEST_TOO_LARGE\",\"title\":\"Request exceeds 64 KiB\"}".getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
