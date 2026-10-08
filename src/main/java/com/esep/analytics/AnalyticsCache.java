package com.esep.analytics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Cache-aside for analytics reports in Redis.
 * <p>
 * Key: {@code analytics:{userId}:v{generation}:{report}:{params}}.
 * Invalidation does not search and delete keys (that would need SCAN over Redis):
 * it increments the user's generation, so all old keys become unreachable at once
 * and simply expire by TTL. O(1) per transfer.
 * <p>
 * Redis is an optimization, not a dependency: any Redis error falls back to the database.
 */
@Slf4j
@Component
public class AnalyticsCache {

    // generation counters outlive cached values by far, so a counter never resets
    // while values of an old generation could still be alive
    private static final Duration GENERATION_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Duration ttl;

    public AnalyticsCache(StringRedisTemplate redis, JsonMapper jsonMapper,
                          @Value("${esep.analytics.cache.ttl}") Duration ttl) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.ttl = ttl;
    }

    public <T> List<T> getOrLoad(long userId, String report, String params, Class<T> itemType,
                                 Supplier<List<T>> loader) {
        JavaType listType = jsonMapper.getTypeFactory().constructCollectionType(List.class, itemType);
        String key;
        try {
            key = "analytics:%d:v%d:%s:%s".formatted(userId, generation(userId), report, params);
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return jsonMapper.readValue(cached, listType);
            }
        } catch (RuntimeException e) {
            log.warn("Analytics cache read failed, using the database: {}", e.toString());
            return loader.get();
        }

        List<T> fresh = loader.get();
        try {
            redis.opsForValue().set(key, jsonMapper.writeValueAsString(fresh), ttl);
        } catch (RuntimeException e) {
            log.warn("Analytics cache write failed: {}", e.toString());
        }
        return fresh;
    }

    /** Called after a transfer is committed: the sender's spending reports are now stale. */
    public void invalidateUser(long userId) {
        try {
            String generationKey = generationKey(userId);
            redis.opsForValue().increment(generationKey);
            redis.expire(generationKey, GENERATION_TTL);
        } catch (RuntimeException e) {
            // worst case: reports stay stale until TTL expires
            log.warn("Analytics cache invalidation failed for user {}: {}", userId, e.toString());
        }
    }

    private long generation(long userId) {
        String value = redis.opsForValue().get(generationKey(userId));
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String generationKey(long userId) {
        return "analytics:%d:generation".formatted(userId);
    }
}
