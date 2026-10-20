package com.sky.ai.validate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.vo.AiIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 意图校验：模型输出不可信，必须过一遍白名单与区间校验
 * <p>
 * 策略：结构错误 → 抛异常触发降级；字段越界 → 丢弃该字段而不是整体失败（保证可用性）
 */
@Component
public class IntentValidator {

    private static final Set<String> FLAVOR_WHITELIST = new HashSet<>(Arrays.asList(
            "麻辣", "香辣", "酸辣", "清淡", "酸甜", "咸香", "五香", "蒜香", "酱香", "微辣", "重口"));

    private static final Set<String> CATEGORY_WHITELIST = new HashSet<>(Arrays.asList(
            "荤菜", "素菜", "汤", "主食", "套餐", "饮品"));

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析并清洗意图
     */
    public AiIntent parse(String content) {
        AiIntent intent;
        try {
            intent = objectMapper.readValue(content, AiIntent.class);
        } catch (Exception e) {
            throw new IllegalStateException("意图结构不合法: " + e.getMessage());
        }
        return sanitize(intent);
    }

    private AiIntent sanitize(AiIntent intent) {
        if (intent == null) {
            return AiIntent.builder().build();
        }
        if (intent.getSpicy() != null && (intent.getSpicy() < 0 || intent.getSpicy() > 3)) {
            intent.setSpicy(null);
        }
        if (intent.getPeople() != null && (intent.getPeople() < 1 || intent.getPeople() > 20)) {
            intent.setPeople(null);
        }
        if (intent.getBudgetPerPerson() != null
                && (intent.getBudgetPerPerson() < 0 || intent.getBudgetPerPerson() > 2000)) {
            intent.setBudgetPerPerson(null);
        }
        intent.setFlavors(filterWhitelist(intent.getFlavors(), FLAVOR_WHITELIST, 3));
        intent.setExcludeIngredients(limitSize(intent.getExcludeIngredients(), 5));
        if (intent.getCategory() != null && !CATEGORY_WHITELIST.contains(intent.getCategory())) {
            intent.setCategory(null);
        }
        if (intent.getKeyword() != null && intent.getKeyword().length() > 20) {
            intent.setKeyword(intent.getKeyword().substring(0, 20));
        }
        return intent;
    }

    private List<String> filterWhitelist(List<String> values, Set<String> whitelist, int maxSize) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && whitelist.contains(value) && !result.contains(value) && result.size() < maxSize) {
                result.add(value);
            }
        }
        return result;
    }

    private List<String> limitSize(List<String> values, int maxSize) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && result.size() < maxSize) {
                result.add(value.trim().length() > 10 ? value.trim().substring(0, 10) : value.trim());
            }
        }
        return result;
    }
}
