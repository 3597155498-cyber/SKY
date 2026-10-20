package com.sky.constant;

/**
 * 订单超时相关常量
 */
public class OrderTimeoutConstant {

    /**
     * 待付款订单超时队列（ZSet，score 为到期时间戳）
     */
    public static final String TIMEOUT_ZSET_KEY = "order:timeout";

    /**
     * 未支付订单超时时间（分钟）
     */
    public static final long TIMEOUT_MINUTES = 15;

    /**
     * 单次从 ZSet 拉取的最大条数，避免一次性拉爆内存
     */
    public static final int BATCH_SIZE = 200;
}
