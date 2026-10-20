package com.sky.feign;

import com.sky.circuit.AiCircuitBreaker;
import com.sky.dto.AiRecommendDTO;
import com.sky.result.Result;
import com.sky.service.AiFallbackService;
import com.sky.vo.AiRecommendVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Feign fallback：AI 服务不可用（连接失败、超时、5xx）时在主服务本地完成降级。
 * <p>
 * 同时写 Redis 熔断开关 ai:circuit:open（短 TTL），后续请求由主服务快速失败，
 * 不再反复等待 Feign 超时；TTL 到期后自动放行一次探测请求，AI 服务恢复即可自动恢复。
 */
@Component
@Slf4j
public class AiRemoteClientFallbackFactory implements FallbackFactory<AiRemoteClient> {

    @Autowired
    private AiFallbackService aiFallbackService;

    @Autowired
    private AiCircuitBreaker aiCircuitBreaker;

    @Override
    public AiRemoteClient create(Throwable cause) {
        log.warn("AI 服务调用失败，触发 Feign fallback: {}", cause == null ? "unknown" : cause.toString());
        aiCircuitBreaker.open();
        return new AiRemoteClient() {
            @Override
            public Result<AiRecommendVO> recommendSync(AiRecommendDTO dto, String userId) {
                return Result.success(aiFallbackService.buildHotRecommend(dto));
            }

            @Override
            public Result<Integer> refreshTags() {
                return Result.error("AI 服务暂不可用，请稍后重试");
            }

            @Override
            public Result<Map<String, Object>> commentSummary(Map<String, Object> payload) {
                return Result.error("AI 服务暂不可用");
            }

            @Override
            public Result<Map<String, Object>> status() {
                return Result.success(aiFallbackService.buildUnavailableStatus());
            }
        };
    }
}
