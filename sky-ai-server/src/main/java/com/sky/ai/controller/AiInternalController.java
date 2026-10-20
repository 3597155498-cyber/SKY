package com.sky.ai.controller;

import com.sky.ai.context.AiUserContext;
import com.sky.ai.service.AiRecommendService;
import com.sky.ai.service.CommentSummaryService;
import com.sky.dto.AiRecommendDTO;
import com.sky.result.Result;
import com.sky.vo.AiRecommendVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 服务内部接口，仅供主服务通过 OpenFeign 调用。
 * <p>
 * 不在这里提供 SSE：SSE 留在主服务，避免 Feign 同步阻塞模型影响流式输出。
 */
@RestController
@RequestMapping("/ai")
@Slf4j
public class AiInternalController {

    @Autowired
    private AiRecommendService aiRecommendService;

    @Autowired
    private CommentSummaryService commentSummaryService;

    /**
     * 同步推荐：主服务的 /user/ai/recommendSync 与 SSE 接口都走这里
     */
    @PostMapping("/recommendSync")
    public Result<AiRecommendVO> recommendSync(@RequestBody AiRecommendDTO dto) {
        Long userId = AiUserContext.getUserId();
        if (userId == null) {
            log.warn("AI 推荐请求缺少用户身份");
            return Result.error("缺少用户身份");
        }
        log.info("AI 推荐请求, userId={}, query={}", userId, dto == null ? null : dto.getQuery());
        return Result.success(aiRecommendService.recommend(dto, userId));
    }

    /**
     * 刷新菜品标签（离线批处理，管理端触发）
     */
    @PostMapping("/tag/refresh")
    public Result<Integer> refreshTags() {
        int count = aiRecommendService.refreshDishTags();
        log.info("菜品标签刷新完成, 写入 {} 条", count);
        return Result.success(count);
    }

    /**
     * 评价周报建议：主服务算统计，AI 服务调模型并落 ai_call_log
     */
    @PostMapping("/comment/summary")
    public Result<Map<String, Object>> commentSummary(@RequestBody Map<String, Object> payload) {
        return Result.success(commentSummaryService.summarize(payload));
    }

    /**
     * AI 状态：模型、熔断、今日调用与 token
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(aiRecommendService.status());
    }

    /**
     * 内部健康检查：用于直连验证与排障
     */
    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        Map<String, Object> map = new HashMap<>();
        map.put("service", "sky-ai-server");
        map.put("status", "UP");
        return Result.success(map);
    }
}
