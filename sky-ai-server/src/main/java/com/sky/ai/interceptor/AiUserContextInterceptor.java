package com.sky.ai.interceptor;

import com.sky.ai.context.AiUserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 解析主服务透传的用户身份。
 * <p>
 * 主服务在 JWT 拦截器里已经校验过 authentication，AI 服务不再重复解析 JWT，
 * 只信任内网调用方写入的 X-User-Id；请求结束必须清理 ThreadLocal。
 */
@Component
@Slf4j
public class AiUserContextInterceptor implements HandlerInterceptor {

    public static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String userId = request.getHeader(USER_ID_HEADER);
        if (StringUtils.hasText(userId)) {
            try {
                AiUserContext.setUserId(Long.valueOf(userId.trim()));
            } catch (NumberFormatException e) {
                log.warn("非法 X-User-Id: {}", userId);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AiUserContext.clear();
    }
}
