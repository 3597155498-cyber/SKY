package com.sky.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在写操作方法上：方法成功执行后按 pattern 清理缓存
 * <p>
 * 与业务代码解耦，替代散落在 Controller 上的 @CacheEvict
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ClearCache {

    /**
     * 要清理的 key 模式，支持通配符，如 cache:dish:list:*
     */
    String pattern();
}
