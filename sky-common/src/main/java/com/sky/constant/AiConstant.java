package com.sky.constant;

/**
 * AI 相关常量
 */
public class AiConstant {

    /** 熔断开关 key：存在即熔断，所有 AI 调用直接降级 */
    public static final String CIRCUIT_KEY = "ai:circuit:open";

    /** 限流 key 前缀：ai:rate:{userId}:{hour} */
    public static final String RATE_KEY_PREFIX = "ai:rate:";

    /** 推荐结果缓存前缀：ai:rec:{md5} */
    public static final String REC_CACHE_PREFIX = "ai:rec:";

    /** 业务类型：意图解析 */
    public static final String BIZ_PARSE = "RECOMMEND_PARSE";

    /** 业务类型：推荐理由生成 */
    public static final String BIZ_REASON = "RECOMMEND_REASON";

    /** 业务类型：菜品打标 */
    public static final String BIZ_TAG = "DISH_TAG";

    /** 推荐模式 */
    public static final String MODE_AI = "AI";
    public static final String MODE_DEFAULT = "DEFAULT";
    public static final String MODE_DEGRADED = "DEGRADED";

    /** 情绪枚举，供前端桌宠映射表情 */
    public static final String EMOTION_HAPPY = "happy";
    public static final String EMOTION_THINKING = "thinking";
    public static final String EMOTION_CONFUSED = "confused";
}
