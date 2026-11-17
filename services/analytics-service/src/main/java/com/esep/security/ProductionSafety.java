package com.esep.security;

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
        https(e.getRequiredProperty("esep.security.keycloak.issuer-uri"));
        https(e.getRequiredProperty("esep.security.keycloak.jwk-set-uri"));
        String origins=e.getRequiredProperty("esep.cors.allowed-origins");
        require(!origins.isBlank(),"Explicit CORS origins required");
        for(String origin:origins.split(",")){
            URI uri=https(origin.trim());
            require(uri.getRawQuery()==null && (uri.getPath().isEmpty() || uri.getPath().equals("/")),"Exact CORS origin required");
        }
        requireDatabaseTls(e.getRequiredProperty("spring.datasource.url"));
        String role=e.getRequiredProperty("spring.datasource.username");
        require(!role.contains("migrator") && !role.contains("bootstrap") && !role.equals("postgres"),"Runtime database role required");
        require(!e.getProperty("spring.flyway.enabled",Boolean.class,true),"Run migrations separately before production runtime");
        require("validate".equals(e.getProperty("spring.jpa.hibernate.ddl-auto")),"Runtime schema mutation prohibited");
        secret(e,"spring.datasource.password");
        require("SASL_SSL".equals(e.getProperty("spring.kafka.properties.security.protocol")),"Authenticated Kafka TLS required");
        require(!e.getRequiredProperty("spring.kafka.properties.sasl.jaas.config").isBlank(),"External Kafka credentials required");
        require("https".equals(e.getProperty("spring.kafka.properties.ssl.endpoint.identification.algorithm")),"Kafka hostname verification required");
        require(e.getProperty("spring.data.redis.ssl.enabled",Boolean.class,false),"Redis TLS required");
        secret(e,"spring.data.redis.password");
        require(e.getProperty("spring.data.redis.url","").isBlank(),"Use explicit TLS Redis host configuration, not URL credentials");
        require(e.getProperty("esep.identity.enabled",Boolean.class,false),"Identity projection required");
        String projection=e.getRequiredProperty("esep.identity.jdbc-url");requireDatabaseTls(projection);
        URI main=URI.create(e.getRequiredProperty("spring.datasource.url").substring(5)),identity=URI.create(projection.substring(5));
        require(java.util.Objects.equals(main.getHost(),identity.getHost()) && main.getPort()==identity.getPort() && java.util.Objects.equals(main.getPath(),identity.getPath()),"Projection must belong to the same service database");
        String projectionRole=e.getRequiredProperty("esep.identity.username");
        require(projectionRole.endsWith("_projector") && !projectionRole.equals(e.getRequiredProperty("spring.datasource.username")),"Dedicated projection role required");
        secret(e,"esep.identity.password");
        https(e.getRequiredProperty("esep.analytics.ledger-base-url"));
    }
    private static URI https(String value){
        URI uri;
        try{uri=URI.create(value);}catch(IllegalArgumentException error){throw new IllegalStateException("Invalid production HTTPS endpoint");}
        require("https".equals(uri.getScheme()) && uri.getHost()!=null && !uri.getHost().contains("*")
                && uri.getUserInfo()==null && uri.getFragment()==null && uri.getRawQuery()==null,"Production HTTPS endpoint required");
        return uri;
    }
    public static void requireDatabaseTls(String value){
        require(value.startsWith("jdbc:postgresql://"),"PostgreSQL required");
        URI uri;
        try{uri=URI.create(value.substring(5));}catch(IllegalArgumentException error){throw new IllegalStateException("Invalid database endpoint");}
        require(uri.getHost()!=null && uri.getUserInfo()==null && uri.getFragment()==null && uri.getRawQuery()!=null,"Explicit verified database TLS required");
        var parameters=new java.util.HashMap<String,String>();
        var allowed=java.util.Set.of("sslmode","sslrootcert","sslcert","sslkey","connectTimeout","socketTimeout","tcpKeepAlive","ApplicationName");
        for(String part:uri.getRawQuery().split("&")){
            String[] pair=part.split("=",2);
            require(pair.length==2 && allowed.contains(pair[0]) && parameters.putIfAbsent(pair[0],pair[1])==null,"Unsafe or duplicate database URL option");
        }
        require("verify-full".equals(parameters.get("sslmode")),"Verified PostgreSQL TLS required");
    }
    private static void secret(Environment e,String key){
        String value=e.getRequiredProperty(key);
        require(value.length()>=24 && !value.toLowerCase(java.util.Locale.ROOT).matches(".*(changeme|placeholder|replace-me).*"),"External strong credentials required");
    }
    private static void require(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
}
