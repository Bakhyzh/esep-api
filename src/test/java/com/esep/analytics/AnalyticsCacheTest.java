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
        when(redis.opsForValue()).thenReturn(values);
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
}
