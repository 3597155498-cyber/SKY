package com.sky.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sky.constant.CacheKeyConstant;
import com.sky.json.JacksonObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 手写缓存客户端（替代注解式 Spring Cache）
 * <p>
 * 为什么不用 @Cacheable：
 * 1、注解式无法对单个 key 做随机 TTL，防不了雪崩
 * 2、注解式无法做互斥重建，防不了击穿
 * 3、注解式默认不缓存 null，防不了穿透
 * <p>
 * 本类提供：
 * · 空值哨兵：查不到数据也写缓存（短 TTL），挡住穿透
 * · SETNX 互斥重建：同一 key 过期时只放一个线程查库，其余短暂等待后兜底直查
 * · 随机 TTL：30 分钟 ± 5 分钟，避免大批 key 同时过期
 * · 按前缀清理：SCAN 而非 KEYS，避免阻塞 Redis
 */
@Component
@Slf4j
public class CacheClient {

    private static final int RETRY_TIMES = 10;
    private static final long RETRY_INTERVAL_MS = 100L;
    private static final Duration LOCK_TTL = Duration.ofSeconds(3);

    private final StringRedisTemplate redisTemplate;
    private final JacksonObjectMapper objectMapper = new JacksonObjectMapper();

    public CacheClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读缓存，未命中则加载并回写
     *
     * @param key    缓存 key
     * @param type   反序列化类型
     * @param loader 回源逻辑（查库）
     */
    public <T> T getOrLoad(String key, TypeReference<T> type, Supplier<T> loader) {
        String cached = redisTemplate.opsForValue().get(key);
        if (CacheKeyConstant.NULL_VALUE.equals(cached)) {
            // 命中空值哨兵，直接返回空，不打库
            return null;
        }
        if (cached != null) {
            return deserialize(cached, type, key);
        }

        // 击穿保护：抢到锁的线程负责重建，其余线程短暂等待
        String lockKey = CacheKeyConstant.LOCK_PREFIX + key;
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        if (Boolean.TRUE.equals(locked)) {
            try {
                T value = loader.get();
                write(key, value);
                return value;
            } finally {
                redisTemplate.delete(lockKey);
            }
        }
        for (int i = 0; i < RETRY_TIMES; i++) {
            sleep();
            String retry = redisTemplate.opsForValue().get(key);
            if (CacheKeyConstant.NULL_VALUE.equals(retry)) {
                return null;
            }
            if (retry != null) {
                return deserialize(retry, type, key);
            }
        }
        // 等不到重建结果也不能让请求失败，直接查库兜底
        return loader.get();
    }

    private <T> T deserialize(String json, TypeReference<T> type, String key) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("缓存反序列化失败, 删除脏数据, key={}", key, e);
            redisTemplate.delete(key);
            return null;
        }
    }

    private <T> void write(String key, T value) {
        try {
            if (value == null) {
                redisTemplate.opsForValue().set(key, CacheKeyConstant.NULL_VALUE,
                        Duration.ofMinutes(CacheKeyConstant.NULL_TTL_MINUTES));
                return;
            }
            long jitter = ThreadLocalRandom.current()
                    .nextLong(-CacheKeyConstant.TTL_JITTER_MINUTES, CacheKeyConstant.TTL_JITTER_MINUTES + 1);
            long ttlMinutes = CacheKeyConstant.BASE_TTL_MINUTES + jitter;
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), Duration.ofMinutes(ttlMinutes));
        } catch (JsonProcessingException e) {
            log.warn("写入缓存失败, key={}", key, e);
        }
    }

    /**
     * 按前缀清理缓存，使用 SCAN 避免 KEYS 阻塞
     *
     * @return 删除的 key 数量
     */
    public long evictByPattern(String pattern) {
        Set<String> keys = redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return result;
        });
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long removed = redisTemplate.delete(keys);
        long count = removed == null ? 0 : removed;
        log.info("缓存失效完成, pattern={}, 删除{}个key", pattern, count);
        return count;
    }

    public void evict(String key) {
        redisTemplate.delete(key);
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
