package com.sky.constant;

/**
 * 缓存 key 与策略常量
 */
public class CacheKeyConstant {

    /**
     * C 端菜品列表缓存：cache:dish:list:{categoryId}
     */
    public static final String DISH_LIST = "cache:dish:list:";

    /**
     * C 端套餐列表缓存：cache:setmeal:list:{categoryId}
     */
    public static final String SETMEAL_LIST = "cache:setmeal:list:";

    /**
     * 缓存重建互斥锁前缀（防击穿）
     */
    public static final String LOCK_PREFIX = "lock:cache:";

    /**
     * 空值哨兵（防穿透）
     */
    public static final String NULL_VALUE = "__NULL__";

    /**
     * 空值缓存有效期（分钟）
     */
    public static final long NULL_TTL_MINUTES = 2;

    /**
     * 基础 TTL（分钟）
     */
    public static final long BASE_TTL_MINUTES = 30;

    /**
     * TTL 随机抖动（分钟），用于防雪崩
     */
    public static final long TTL_JITTER_MINUTES = 5;
}
