package com.sky.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sky.exception.AiUnavailableException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 大模型客户端（OpenAI 兼容协议）
 * <p>
 * 不引入 Spring AI：项目是 Spring Boot 2.7 + javax，Spring AI 要求 Boot 3.4+。
 * 自己封装反而能把超时、重试、mock 开关、token 统计讲清楚。
 */
@Component
@Slf4j
public class LlmClient {

    @Value("${sky.ai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${sky.ai.api-key:}")
    private String apiKey;

    @Value("${sky.ai.model:deepseek-chat}")
    private String model;

    @Value("${sky.ai.mock:false}")
    private boolean mock;

    @Value("${sky.ai.timeout-ms:15000}")
    private int timeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 单轮对话
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户内容（槽位化后的结构化参数，不直接拼接指令）
     * @param jsonMode     是否要求模型返回 JSON
     * @param mockContent  mock 模式下返回的固定内容
     */
    public LlmResult chat(String systemPrompt, String userPrompt, boolean jsonMode, String mockContent) {
        long start = System.currentTimeMillis();
        if (mock || apiKey == null || apiKey.trim().isEmpty()) {
            return new LlmResult(mockContent, 0, 0, model + "-mock", System.currentTimeMillis() - start);
        }

        String body = buildBody(systemPrompt, userPrompt, jsonMode);
        Exception lastError = null;
        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long attemptStart = System.currentTimeMillis();
            try {
                String response = doPost(body);
                JsonNode root = objectMapper.readTree(response);
                JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
                if (contentNode.isMissingNode() || contentNode.asText().trim().isEmpty()) {
                    throw new IllegalStateException("模型返回空内容");
                }
                int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
                int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
                String usedModel = root.path("model").asText(model);
                return new LlmResult(cleanJsonFence(contentNode.asText()), promptTokens, completionTokens,
                        usedModel, System.currentTimeMillis() - start);
            } catch (Exception e) {
                lastError = e;
                long cost = System.currentTimeMillis() - attemptStart;
                log.warn("大模型调用失败, attempt={}, cost={}ms, error={}", attempt, cost, e.getMessage());
                // 慢失败（已接近超时）不再重试，避免把接口延迟翻倍
                if (cost > 2000L) {
                    break;
                }
            }
        }
        throw new AiUnavailableException("大模型不可用: " + (lastError == null ? "unknown" : lastError.getMessage()));
    }

    private String buildBody(String systemPrompt, String userPrompt, boolean jsonMode) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0);
        root.put("max_tokens", 800);
        if (jsonMode) {
            ObjectNode format = objectMapper.createObjectNode();
            format.put("type", "json_object");
            root.set("response_format", format);
        }
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode system = objectMapper.createObjectNode();
        system.put("role", "system");
        system.put("content", systemPrompt);
        messages.add(system);
        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        user.put("content", userPrompt);
        messages.add(user);
        root.set("messages", messages);
        return root.toString();
    }

    private String doPost(String body) throws Exception {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(baseUrl + "/chat/completions");
            post.setConfig(config);
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Authorization", "Bearer " + apiKey);
            post.setEntity(new StringEntity(body, "UTF-8"));
            try (CloseableHttpResponse response = client.execute(post)) {
                int status = response.getStatusLine().getStatusCode();
                String text = EntityUtils.toString(response.getEntity(), "UTF-8");
                if (status != 200) {
                    throw new IllegalStateException("HTTP " + status + ": " + text);
                }
                return text;
            }
        }
    }

    /**
     * 模型偶尔会把 JSON 包在 ```json 代码块里，统一剥离
     */
    private String cleanJsonFence(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }

    @Data
    public static class LlmResult {
        private final String content;
        private final int promptTokens;
        private final int completionTokens;
        private final String model;
        private final long elapsedMs;
    }
}
