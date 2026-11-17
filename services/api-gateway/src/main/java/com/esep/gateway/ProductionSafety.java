package com.esep.gateway;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** Production opt-in fails before runtime singleton/connection initialization. Never logs configuration values. */
@Configuration(proxyBeanMethods=false)
@Profile("production")
public class ProductionSafety {
    @Bean static BeanFactoryPostProcessor enforceProductionSafety(Environment environment){return factory->validate(environment);}
    static void validate(Environment e){
        require(e.getProperty("server.ssl.enabled",Boolean.class,false),"Production service HTTPS required");
        require(e.getRequiredProperty("server.ssl.key-store").startsWith("file:"),"External service TLS key store required");
        secret(e,"server.ssl.key-store-password");
        https(e.getRequiredProperty("esep.gateway.issuer-uri"));
        https(e.getRequiredProperty("esep.gateway.jwk-set-uri"));
        String origins=e.getRequiredProperty("esep.gateway.cors-origins");
        require(!origins.isBlank(),"Explicit CORS origins required");
        for(String origin:origins.split(",")){
            URI uri=https(origin.trim());
            require(uri.getRawQuery()==null && (uri.getPath().isEmpty() || uri.getPath().equals("/")),"Exact CORS origin required");
        }
        require(e.getProperty("spring.data.redis.ssl.enabled",Boolean.class,false),"Redis TLS required");
        secret(e,"spring.data.redis.password");
        require(e.getProperty("spring.data.redis.url","").isBlank(),"Use explicit TLS Redis host configuration, not URL credentials");
        for(String target:List.of("user-uri","ledger-uri","analytics-uri","notification-uri")) https(e.getRequiredProperty("esep.gateway."+target));
    }
    private static URI https(String value){
        URI uri;
        try{uri=URI.create(value);}catch(IllegalArgumentException error){throw new IllegalStateException("Invalid production HTTPS endpoint");}
        require("https".equals(uri.getScheme()) && uri.getHost()!=null && !uri.getHost().contains("*")
                && uri.getUserInfo()==null && uri.getFragment()==null && uri.getRawQuery()==null,"Production HTTPS endpoint required");
        return uri;
    }
    private static void secret(Environment e,String key){
        String value=e.getRequiredProperty(key);
        require(value.length()>=24 && !value.toLowerCase(java.util.Locale.ROOT).matches(".*(changeme|placeholder|replace-me).*"),"External strong credentials required");
    }
    private static void require(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
}
