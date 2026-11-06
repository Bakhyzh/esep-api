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
    public AnalyticsCache(StringRedisTemplate redis,JdbcTemplate jdbc,JsonMapper mapper,@Value("${esep.analytics.cache.ttl}") Duration ttl){
        if(ttl.isNegative() || ttl.isZero())throw new IllegalArgumentException("Cache TTL must be positive");
        this.redis=redis;this.jdbc=jdbc;this.mapper=mapper;this.ttl=ttl;
    }
    public <T> List<T> getOrLoad(long user,String report,String params,Class<T> type,Supplier<List<T>> loader){
        long generation=jdbc.queryForObject("SELECT coalesce((SELECT generation FROM analytics_generations WHERE user_id=?),0)",Long.class,user);
        String key="esep:analytics:v1:user:"+user+":g:"+generation+":"+report+":"+params;
        var listType=mapper.getTypeFactory().constructCollectionType(List.class,type);
        try{String value=redis.opsForValue().get(key);if(value!=null)return mapper.readValue(value,listType);}
        catch(RuntimeException ignored){return loader.get();}
        var result=loader.get();
        try{redis.opsForValue().set(key,mapper.writeValueAsString(result),ttl);}catch(RuntimeException ignored){/* SQL result remains authoritative. */}
        return result;
    }
}
