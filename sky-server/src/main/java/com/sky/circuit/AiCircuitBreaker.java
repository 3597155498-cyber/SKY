package com.sky.circuit;

import com.sky.constant.AiConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 主服务侧的 AI 熔断开关，复用 AI 服务已有的 Redis key ai:circuit:open。
 * <p>
 * 只做两件事：AI 调用失败时写一个短 TTL 的 key；后续请求先看 key，存在就直接本地降级。
 * TTL 到期后自动放行一次探测，避免引入 Sentinel/Hystrix 的额外治理面。
 */
@Component
@Slf4j
public class AiCircuitBreaker {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${sky.ai.circuit-ttl-seconds:15}")
    private int circuitTtlSeconds;

    public boolean isOpen() {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(AiConstant.CIRCUIT_KEY));
        } catch (Exception e) {
            // Redis 异常时不能把 AI 调用也一起熔断，交给 Feign fallback 兜底
            log.warn("读取 AI 熔断开关失败: {}", e.getMessage());
            return false;
        }
    }

    public void open() {
        try {
            stringRedisTemplate.opsForValue()
                    .set(AiConstant.CIRCUIT_KEY, "1", Duration.ofSeconds(circuitTtlSeconds));
        } catch (Exception e) {
            log.warn("写 AI 熔断开关失败: {}", e.getMessage());
        }
    }
}
