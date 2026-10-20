package com.sky.ai.context;

/**
 * AI 服务的用户上下文：由拦截器从 X-User-Id 解析，限流按 userId 计数。
 */
public final class AiUserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private AiUserContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
