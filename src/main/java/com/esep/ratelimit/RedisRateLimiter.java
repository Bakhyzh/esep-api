package com.esep.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Fixed-window counter in Redis: the first request in a window creates the key with a TTL,
 * every request increments it, and the key disappears when the window ends.
 * Redis (not a map in memory) so the limit holds across restarts and several app instances.
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    // INCR and PEXPIRE in one script: atomic, so a crash between them cannot leave a key without a TTL
    private static final RedisScript<List> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;

    public Decision tryAcquire(String key, int maxRequests, Duration window) {
        List<?> result = redis.execute(INCREMENT, List.of(key), String.valueOf(window.toMillis()));
        long count = ((Number) result.get(0)).longValue();
        long ttlMillis = Math.max(((Number) result.get(1)).longValue(), 0);
        return new Decision(count <= maxRequests, Duration.ofMillis(ttlMillis));
    }

    /** {@code retryAfter} = time until the current window ends. */
    public record Decision(boolean allowed, Duration retryAfter) {
    }
}
