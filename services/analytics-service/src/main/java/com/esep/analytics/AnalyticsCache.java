package com.esep.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/** Cache generations are committed in analytics_db with postings; Redis is optional. */
@Component
public class AnalyticsCache {
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Duration ttl;
    private final io.micrometer.core.instrument.Counter hits,misses,errors;
    public AnalyticsCache(StringRedisTemplate redis,JdbcTemplate jdbc,JsonMapper mapper,@Value("${esep.analytics.cache.ttl}") Duration ttl,io.micrometer.core.instrument.MeterRegistry registry){
        if(ttl.isNegative() || ttl.isZero())throw new IllegalArgumentException("Cache TTL must be positive");
        this.redis=redis;this.jdbc=jdbc;this.mapper=mapper;this.ttl=ttl;
        hits=registry.counter("esep.analytics.cache.requests","result","hit");misses=registry.counter("esep.analytics.cache.requests","result","miss");errors=registry.counter("esep.analytics.cache.errors");
    }
    public <T> List<T> getOrLoad(long user,String report,String params,Class<T> type,Supplier<List<T>> loader){
        long generation=jdbc.queryForObject("SELECT coalesce((SELECT generation FROM analytics_generations WHERE user_id=?),0)",Long.class,user);
        String key="esep:analytics:v1:user:"+user+":g:"+generation+":"+report+":"+params;
        var listType=mapper.getTypeFactory().constructCollectionType(List.class,type);
        try{String value=redis.opsForValue().get(key);if(value!=null){hits.increment();return mapper.readValue(value,listType);}}
        catch(RuntimeException ignored){errors.increment();return loader.get();}
        misses.increment();var result=loader.get();
        try{redis.opsForValue().set(key,mapper.writeValueAsString(result),ttl);}catch(RuntimeException ignored){errors.increment();/* SQL result remains authoritative. */}
        return result;
    }
}
