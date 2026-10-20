package com.sky.ai.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.ai.client.LlmClient;
import com.sky.ai.mapper.AiCallLogMapper;
import com.sky.ai.service.CommentSummaryService;
import com.sky.entity.AiCallLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 周报建议：AI 服务统一负责模型调用与 ai_call_log 落库，主服务只传统计结果。
 */
@Service
@Slf4j
public class CommentSummaryServiceImpl implements CommentSummaryService {

    private static final String SYSTEM_PROMPT =
            "你是餐饮运营顾问。只输出JSON：{\"summary\":\"120字以内，指出最突出的问题并给1-2条可执行建议，不要空话\"}";

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private AiCallLogMapper aiCallLogMapper;

    @Value("${sky.ai.model:deepseek-chat}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> summarize(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String userPrompt = objectMapper.writeValueAsString(buildBody(payload));
            LlmClient.LlmResult llmResult = llmClient.chat(
                    SYSTEM_PROMPT, userPrompt, true, "{\"summary\":\"\"}");
            logCall(llmResult, true, null);
            JsonNode node = objectMapper.readTree(llmResult.getContent());
            String summary = node.path("summary").asText();
            result.put("summary", summary);
            result.put("model", llmResult.getModel());
            result.put("promptTokens", llmResult.getPromptTokens());
            result.put("completionTokens", llmResult.getCompletionTokens());
            result.put("costMs", llmResult.getElapsedMs());
            result.put("degraded", false);
            return result;
        } catch (Exception e) {
            log.warn("周报建议生成失败: {}", e.getMessage());
            logCall(null, false, e.getMessage());
            result.put("summary", "");
            result.put("degraded", true);
            result.put("error", e.getMessage());
            return result;
        }
    }

    private Map<String, Object> buildBody(Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalComments", toInt(payload == null ? null : payload.get("totalComments")));
        body.put("negativeCount", toInt(payload == null ? null : payload.get("negativeCount")));
        Object themes = payload == null ? null : payload.get("topNegativeThemes");
        if (themes instanceof List) {
            body.put("topNegativeThemes", themes);
        } else {
            body.put("topNegativeThemes", java.util.Collections.emptyList());
        }
        return body;
    }

    private int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private void logCall(LlmClient.LlmResult result, boolean success, String errorMsg) {
        try {
            aiCallLogMapper.insert(AiCallLog.builder()
                    .bizType("COMMENT_SUMMARY")
                    .model(result == null ? model : result.getModel())
                    .promptTokens(result == null ? 0 : result.getPromptTokens())
                    .completionTokens(result == null ? 0 : result.getCompletionTokens())
                    .costMs(result == null ? 0 : (int) result.getElapsedMs())
                    .degraded(success ? 0 : 1)
                    .success(success ? 1 : 0)
                    .errorMsg(errorMsg == null ? null
                            : (errorMsg.length() > 250 ? errorMsg.substring(0, 250) : errorMsg))
                    .traceId(String.valueOf(System.currentTimeMillis()))
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("调用日志写入失败: {}", e.getMessage());
        }
    }
}
