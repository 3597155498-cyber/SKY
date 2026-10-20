package com.sky.service.impl;

import com.sky.circuit.AiCircuitBreaker;
import com.sky.dto.AiRecommendDTO;
import com.sky.exception.AiUnavailableException;
import com.sky.feign.AiRemoteClient;
import com.sky.result.Result;
import com.sky.service.AiFallbackService;
import com.sky.service.AiGatewayService;
import com.sky.vo.AiRecommendVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * AI 能力入口：Nacos 发现 sky-ai-server → OpenFeign 调用 → 失败本地降级。
 */
@Service
@Slf4j
public class AiGatewayServiceImpl implements AiGatewayService {

    @Autowired
    private AiRemoteClient aiRemoteClient;

    @Autowired
    private AiFallbackService aiFallbackService;

    @Autowired
    private AiCircuitBreaker aiCircuitBreaker;

    @Override
    public AiRecommendVO recommend(AiRecommendDTO dto, Long userId) {
        long start = System.currentTimeMillis();
        if (aiCircuitBreaker.isOpen()) {
            log.warn("AI 熔断开关开启，主服务直接本地降级");
            return localFallback(dto, start);
        }
        try {
            Result<AiRecommendVO> result = aiRemoteClient.recommendSync(
                    dto, userId == null ? "" : String.valueOf(userId));
            if (result == null || !Integer.valueOf(1).equals(result.getCode()) || result.getData() == null) {
                log.warn("AI 服务返回失败, code={}, msg={}",
                        result == null ? null : result.getCode(),
                        result == null ? null : result.getMsg());
                return localFallback(dto, start);
            }
            AiRecommendVO vo = result.getData();
            vo.setElapsedMs(System.currentTimeMillis() - start);
            return vo;
        } catch (Exception e) {
            log.error("AI 服务调用异常，主服务本地降级: {}", e.getMessage());
            aiCircuitBreaker.open();
            return localFallback(dto, start);
        }
    }

    @Override
    public int refreshDishTags() {
        if (aiCircuitBreaker.isOpen()) {
            throw new AiUnavailableException("AI 服务熔断中，暂不能刷新标签");
        }
        try {
            Result<Integer> result = aiRemoteClient.refreshTags();
            if (result == null || !Integer.valueOf(1).equals(result.getCode()) || result.getData() == null) {
                throw new AiUnavailableException(result == null || result.getMsg() == null
                        ? "AI 服务暂不可用" : result.getMsg());
            }
            return result.getData();
        } catch (AiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 标签刷新调用失败: {}", e.getMessage());
            throw new AiUnavailableException("AI 服务暂不可用");
        }
    }

    @Override
    public String commentSummary(Map<String, Object> payload) {
        if (aiCircuitBreaker.isOpen()) {
            return null;
        }
        try {
            Result<Map<String, Object>> result = aiRemoteClient.commentSummary(payload);
            if (result != null && Integer.valueOf(1).equals(result.getCode()) && result.getData() != null) {
                Object summary = result.getData().get("summary");
                if (summary != null && StringUtils.hasText(summary.toString())) {
                    return summary.toString();
                }
            }
        } catch (Exception e) {
            log.warn("AI 周报建议调用失败，使用模板兜底: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public Map<String, Object> status() {
        if (aiCircuitBreaker.isOpen()) {
            return aiFallbackService.buildUnavailableStatus();
        }
        try {
            Result<Map<String, Object>> result = aiRemoteClient.status();
            if (result != null && Integer.valueOf(1).equals(result.getCode()) && result.getData() != null) {
                return result.getData();
            }
        } catch (Exception e) {
            log.warn("查询 AI 状态失败: {}", e.getMessage());
        }
        return aiFallbackService.buildUnavailableStatus();
    }

    private AiRecommendVO localFallback(AiRecommendDTO dto, long start) {
        AiRecommendVO vo = aiFallbackService.buildHotRecommend(dto);
        vo.setElapsedMs(System.currentTimeMillis() - start);
        return vo;
    }
}
