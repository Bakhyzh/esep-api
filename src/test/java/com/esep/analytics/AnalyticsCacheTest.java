package com.esep.analytics;

import com.esep.analytics.dto.SpendingPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Redis failures must never break a report: the cache is an optimization. */
@ExtendWith(MockitoExtension.class)
class AnalyticsCacheTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    private AnalyticsCache cache;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        cache = new AnalyticsCache(redis, JsonMapper.builder().build(), Duration.ofMinutes(5));
    }

    @Test
    void redisDown_onRead_fallsBackToTheDatabase() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        AtomicInteger loads = new AtomicInteger();

        List<SpendingPoint> result = cache.getOrLoad(1L, "spending", "p", SpendingPoint.class, () -> {
            loads.incrementAndGet();
            return List.of(new SpendingPoint(LocalDate.parse("2026-03-01"), BigDecimal.TEN, 1));
        });

        assertThat(result).hasSize(1);
        assertThat(loads).hasValue(1);
    }

    @Test
    void redisDown_onInvalidate_doesNotThrow() {
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        cache.invalidateUser(1L);   // no exception: the transfer is already committed
    }

    @Test
    void cacheHit_doesNotTouchTheDatabase() {
        when(values.get("analytics:1:generation")).thenReturn("3");
        when(values.get("analytics:1:v3:spending:p"))
                .thenReturn("[{\"periodStart\":\"2026-03-01\",\"total\":10.5000,\"operations\":2}]");

        List<SpendingPoint> result = cache.getOrLoad(1L, "spending", "p", SpendingPoint.class, () -> {
            throw new AssertionError("must not load on a hit");
        });

        assertThat(result).containsExactly(new SpendingPoint(LocalDate.parse("2026-03-01"), new BigDecimal("10.5000"), 2));
    }

    @Test
    void zeroTtl_isRejectedOnStartup() {
        assertThatThrownBy(() -> new AnalyticsCache(redis, JsonMapper.builder().build(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("esep.analytics.cache.ttl");
    }
}
