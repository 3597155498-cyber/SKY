package com.sky.ai.guard;

import com.sky.constant.AiConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AI 调用限流：按用户 + 小时维度计数，超限直接走降级，不消耗模型成本
 */
@Component
public class AiRateLimiter {

    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${sky.ai.rate-limit-per-hour:10}")
    private int limitPerHour;

    public boolean tryAcquire(Long userId) {
        String key = AiConstant.RATE_KEY_PREFIX + userId + ":" + LocalDateTime.now().format(HOUR_FORMAT);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofHours(2));
        }
        return count != null && count <= limitPerHour;
    }

    public int getLimitPerHour() {
        return limitPerHour;
    }
}
