package com.sky.aspect;

import com.sky.annotation.ClearCache;
import com.sky.cache.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 缓存清理切面：写操作成功返回后统一清缓存
 */
@Aspect
@Component
@Slf4j
public class ClearCacheAspect {

    @Autowired
    private CacheClient cacheClient;

    @AfterReturning(value = "@annotation(clearCache)", argNames = "joinPoint,clearCache")
    public void clearCache(JoinPoint joinPoint, ClearCache clearCache) {
        long removed = cacheClient.evictByPattern(clearCache.pattern());
        log.info("写操作清缓存, method={}, pattern={}, 删除={}",
                joinPoint.getSignature().toShortString(), clearCache.pattern(), removed);
    }
}
