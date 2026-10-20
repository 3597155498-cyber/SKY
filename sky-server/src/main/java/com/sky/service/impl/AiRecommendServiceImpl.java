package com.sky.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sky.ai.client.LlmClient;
import com.sky.ai.guard.AiRateLimiter;
import com.sky.ai.validate.IntentValidator;
import com.sky.constant.AiConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.AiRecommendDTO;
import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.AiCallLog;
import com.sky.entity.Dish;
import com.sky.entity.DishTag;
import com.sky.mapper.AiCallLogMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.DishTagMapper;
import com.sky.mapper.OrderMapper;
import com.sky.service.AiRecommendService;
import com.sky.vo.AiIntent;
import com.sky.vo.AiItemVO;
import com.sky.vo.AiRecommendVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 自然语言点餐：意图解析 → 校验 → 检索打分 → 理由生成 → 人格化话术
 * <p>
 * 核心约束：模型只输出"条件"，绝不输出菜品 id；菜品一律由本地检索决定。
 */
@Service
@Slf4j
public class AiRecommendServiceImpl implements AiRecommendService {

    private static final String PARSE_SYSTEM_PROMPT =
            "你是餐饮点餐意图解析器。只输出 JSON，不要解释，不要输出菜品 id。"
                    + "字段：spicy(0不辣/1微辣/2中辣/3特辣，不确定填null)、flavors(口味数组，如麻辣/清淡/酸甜)、"
                    + "excludeIngredients(忌口食材数组)、budgetPerPerson(人均预算整数)、people(人数整数)、"
                    + "category(荤菜/素菜/汤/主食/套餐/饮品 之一)、keyword(未能归类的关键词)。"
                    + "输入可能来自语音识别，含错别字、语气词、断句错误，请按餐饮语境推断最可能的含义；"
                    + "实在无法判断的字段填 null，不要编造。";

    private static final String REASON_SYSTEM_PROMPT =
            "你是餐厅服务员的推荐话术生成器。只输出 JSON：{\"items\":[{\"name\":\"菜名\",\"reason\":\"不超过20字的推荐理由\"}]}。"
                    + "理由要口语化、具体，只能基于给出的菜名/价格/标签，不得编造不存在的口味或食材。";

    @Autowired
    private LlmClient llmClient;
    @Autowired
    private IntentValidator intentValidator;
    @Autowired
    private AiRateLimiter aiRateLimiter;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishTagMapper dishTagMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private AiCallLogMapper aiCallLogMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${sky.ai.model:deepseek-chat}")
    private String model;
    @Value("${sky.ai.max-items:5}")
    private int maxItems;
    @Value("${sky.ai.cache-ttl-minutes:10}")
    private int cacheTtlMinutes;
    @Value("${sky.ai.persona.mascot-name:小天}")
    private String mascotName;
    @Value("${sky.ai.persona.greeting:想吃点什么～}")
    private String greeting;
    @Value("${sky.ai.persona.not-understood:我没太听清，先给你几个热销的～}")
    private String notUnderstood;
    @Value("${sky.ai.persona.degraded:网络有点慢，先按热销推荐～}")
    private String degradedSpeech;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AiRecommendVO recommend(AiRecommendDTO dto, Long userId) {
        long start = System.currentTimeMillis();
        String query = dto == null ? null : dto.getQuery();
        String normalized = normalize(query);

        // 1、没说话 / 太短：默认推荐，不调模型（0 成本）
        if (normalized.length() < 2) {
            AiRecommendVO vo = buildDefault(null, AiConstant.MODE_DEFAULT, greeting, AiConstant.EMOTION_THINKING);
            vo.setElapsedMs(System.currentTimeMillis() - start);
            return vo;
        }

        // 2、结果缓存
        String cacheKey = AiConstant.REC_CACHE_PREFIX + DigestUtils.md5DigestAsHex(
                (normalized + "|" + (dto.getCategoryId() == null ? "" : dto.getCategoryId())).getBytes(StandardCharsets.UTF_8));
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            try {
                AiRecommendVO hit = objectMapper.readValue(cached, AiRecommendVO.class);
                hit.setElapsedMs(System.currentTimeMillis() - start);
                return hit;
            } catch (Exception e) {
                stringRedisTemplate.delete(cacheKey);
            }
        }

        // 3、熔断 / 限流 → 降级为热销
        if (isCircuitOpen()) {
            log.warn("AI 熔断开启, 走降级推荐");
            return finish(buildDefault(dto.getCategoryId(), AiConstant.MODE_DEGRADED, degradedSpeech,
                    AiConstant.EMOTION_CONFUSED), cacheKey, null, start);
        }
        if (userId != null && !aiRateLimiter.tryAcquire(userId)) {
            log.warn("AI 触发限流, userId={}", userId);
            return finish(buildDefault(dto.getCategoryId(), AiConstant.MODE_DEGRADED,
                    "今天问得有点多啦，先给你几个热销的～", AiConstant.EMOTION_CONFUSED), cacheKey, null, start);
        }

        // 4、意图解析（失败降级）
        AiIntent intent;
        try {
            LlmClient.LlmResult result = llmClient.chat(PARSE_SYSTEM_PROMPT,
                    "{\"query\":" + toJsonString(query) + "}", true, mockIntent());
            logCall(AiConstant.BIZ_PARSE, result, true, null);
            intent = intentValidator.parse(result.getContent());
        } catch (Exception e) {
            log.error("意图解析失败, 降级为关键词检索, error={}", e.getMessage());
            logCall(AiConstant.BIZ_PARSE, null, false, e.getMessage());
            return finish(buildDegradedByKeyword(normalized, dto.getCategoryId()), cacheKey, null, start);
        }

        // 5、模型没解析出任何条件 → 视为没听懂，默认推荐
        if (intent == null || intent.isEmpty()) {
            return finish(buildDefault(dto.getCategoryId(), AiConstant.MODE_DEFAULT, notUnderstood,
                    AiConstant.EMOTION_CONFUSED), cacheKey, intent, start);
        }

        // 6、检索打分
        List<AiItemVO> items = retrieve(intent, dto.getCategoryId());
        if (items.isEmpty()) {
            return finish(buildDefault(dto.getCategoryId(), AiConstant.MODE_DEGRADED,
                    "按你说的条件没找到合适的，先给你几个热销的～", AiConstant.EMOTION_CONFUSED), cacheKey, intent, start);
        }

        // 7、理由生成（失败用模板兜底）
        fillReasons(items, intent);

        AiRecommendVO vo = AiRecommendVO.builder()
                .mode(AiConstant.MODE_AI)
                .degraded(false)
                .needConfirm(false)
                .emotion(AiConstant.EMOTION_HAPPY)
                .speech(mascotName + "帮你挑了 " + items.size() + " 个，看看合不合口味～")
                .parsed(intent)
                .items(items)
                .build();
        return finish(vo, cacheKey, intent, start);
    }

    @Override
    public int refreshDishTags() {
        Dish query = new Dish();
        List<Dish> dishes = dishMapper.list(query);
        if (CollectionUtils.isEmpty(dishes)) {
            return 0;
        }
        StringBuilder payload = new StringBuilder("{\"dishes\":[");
        for (int i = 0; i < dishes.size(); i++) {
            Dish dish = dishes.get(i);
            if (i > 0) {
                payload.append(',');
            }
            payload.append("{\"id\":").append(dish.getId())
                    .append(",\"name\":").append(toJsonString(dish.getName()))
                    .append(",\"description\":").append(toJsonString(dish.getDescription()))
                    .append('}');
        }
        payload.append("]}");

        String systemPrompt = "你是菜品标签标注器。只输出 JSON：{\"dishes\":[{\"id\":数字,\"spicy\":0-3,"
                + "\"flavors\":[\"麻辣\"|\"清淡\"|\"酸甜\"|\"咸香\"|\"蒜香\"],\"ingredients\":[\"主要食材\"]}]}。"
                + "只根据菜名和描述判断，不确定的数组留空。";
        LlmClient.LlmResult result;
        try {
            result = llmClient.chat(systemPrompt, payload.toString(), true, mockTags());
        } catch (Exception e) {
            log.error("菜品打标失败: {}", e.getMessage());
            logCall(AiConstant.BIZ_TAG, null, false, e.getMessage());
            return 0;
        }
        logCall(AiConstant.BIZ_TAG, result, true, null);

        int count = 0;
        try {
            JsonNode root = objectMapper.readTree(result.getContent());
            JsonNode list = root.path("dishes");
            for (JsonNode node : list) {
                long dishId = node.path("id").asLong();
                if (dishId <= 0) {
                    continue;
                }
                dishTagMapper.deleteByDishId(dishId);
                Integer spicy = node.path("spicy").isMissingNode() ? null : node.path("spicy").asInt();
                if (spicy != null) {
                    count += upsert(dishId, "SPICY", spicyName(spicy));
                }
                count += upsertArray(dishId, "FLAVOR", node.path("flavors"));
                count += upsertArray(dishId, "INGREDIENT", node.path("ingredients"));
            }
        } catch (Exception e) {
            log.error("解析打标结果失败: {}", e.getMessage());
        }
        log.info("菜品打标完成, 写入 {} 条标签", count);
        return count;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("model", model);
        map.put("circuitOpen", isCircuitOpen());
        map.put("rateLimitPerHour", aiRateLimiter.getLimitPerHour());
        LocalDateTime begin = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        Map<String, Object> stat = aiCallLogMapper.statisticsSince(begin);
        map.put("today", stat == null ? new HashMap<>() : stat);
        return map;
    }

    // ---------------- 检索与打分 ----------------

    private List<AiItemVO> retrieve(AiIntent intent, Long categoryId) {
        Dish query = new Dish();
        query.setStatus(StatusConstant.ENABLE);
        if (categoryId != null) {
            query.setCategoryId(categoryId);
        }
        List<Dish> dishes = dishMapper.list(query);
        if (CollectionUtils.isEmpty(dishes)) {
            return new ArrayList<>();
        }
        Map<Long, Map<String, List<String>>> tagMap = loadTags();
        Map<String, Integer> salesRank = loadSalesRank();

        List<AiItemVO> scored = new ArrayList<>();
        for (Dish dish : dishes) {
            if (dish.getStock() != null && dish.getStock() <= 0) {
                continue;
            }
            Map<String, List<String>> tags = tagMap.get(dish.getId());
            if (tags == null) {
                tags = new HashMap<>();
            }
            if (hitExclude(tags, intent.getExcludeIngredients())) {
                continue;
            }
            if (intent.getBudgetPerPerson() != null && dish.getPrice() != null
                    && dish.getPrice().compareTo(new BigDecimal(intent.getBudgetPerPerson())) > 0) {
                continue;
            }
            int score = score(dish, tags, intent, salesRank);
            scored.add(AiItemVO.builder()
                    .dishId(dish.getId())
                    .name(dish.getName())
                    .price(dish.getPrice())
                    .image(dish.getImage())
                    .score(score)
                    .build());
        }
        scored.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
        return scored.size() > maxItems ? new ArrayList<>(scored.subList(0, maxItems)) : scored;
    }

    private int score(Dish dish, Map<String, List<String>> tags, AiIntent intent, Map<String, Integer> salesRank) {
        int score = 0;
        List<String> spicyTags = tags.get("SPICY");
        if (intent.getSpicy() != null) {
            String want = spicyName(intent.getSpicy());
            if (spicyTags != null && spicyTags.contains(want)) {
                score += 3;
            } else if (intent.getSpicy() >= 2) {
                score -= 1;
            }
        }
        List<String> flavorTags = tags.get("FLAVOR");
        if (intent.getFlavors() != null) {
            for (String flavor : intent.getFlavors()) {
                if (flavorTags != null && flavorTags.contains(flavor)) {
                    score += 2;
                }
            }
        }
        if (StringUtils.hasText(intent.getKeyword()) && dish.getName() != null
                && dish.getName().contains(intent.getKeyword())) {
            score += 2;
        }
        Integer rank = salesRank.get(dish.getName());
        if (rank != null) {
            score += Math.max(0, 6 - rank);
        }
        return score;
    }

    private boolean hitExclude(Map<String, List<String>> tags, List<String> excludes) {
        if (excludes == null || excludes.isEmpty()) {
            return false;
        }
        List<String> ingredients = tags.get("INGREDIENT");
        if (ingredients == null || ingredients.isEmpty()) {
            return false;
        }
        for (String exclude : excludes) {
            for (String ingredient : ingredients) {
                if (ingredient.contains(exclude) || exclude.contains(ingredient)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<Long, Map<String, List<String>>> loadTags() {
        Map<Long, Map<String, List<String>>> result = new HashMap<>();
        List<DishTag> tags = dishTagMapper.listAll();
        if (tags == null) {
            return result;
        }
        for (DishTag tag : tags) {
            Map<String, List<String>> byType = result.get(tag.getDishId());
            if (byType == null) {
                byType = new HashMap<>();
                result.put(tag.getDishId(), byType);
            }
            List<String> values = byType.get(tag.getTagType());
            if (values == null) {
                values = new ArrayList<>();
                byType.put(tag.getTagType(), values);
            }
            values.add(tag.getTagValue());
        }
        return result;
    }

    private Map<String, Integer> loadSalesRank() {
        Map<String, Integer> rank = new HashMap<>();
        try {
            LocalDateTime begin = LocalDateTime.now().minusDays(30);
            List<GoodsSalesDTO> top = orderMapper.getSalesTop10(begin, LocalDateTime.now());
            if (top != null) {
                for (int i = 0; i < top.size(); i++) {
                    rank.put(top.get(i).getName(), i + 1);
                }
            }
        } catch (Exception e) {
            log.warn("销量统计失败, 忽略销量分: {}", e.getMessage());
        }
        return rank;
    }

    // ---------------- 理由与话术 ----------------

    private void fillReasons(List<AiItemVO> items, AiIntent intent) {
        StringBuilder payload = new StringBuilder("{\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            AiItemVO item = items.get(i);
            if (i > 0) {
                payload.append(',');
            }
            payload.append("{\"name\":").append(toJsonString(item.getName()))
                    .append(",\"price\":").append(item.getPrice() == null ? "null" : item.getPrice())
                    .append('}');
        }
        payload.append("],\"taste\":").append(toJsonString(describeIntent(intent))).append('}');

        try {
            LlmClient.LlmResult result = llmClient.chat(REASON_SYSTEM_PROMPT, payload.toString(), true, mockReasons());
            logCall(AiConstant.BIZ_REASON, result, true, null);
            JsonNode root = objectMapper.readTree(result.getContent());
            Map<String, String> reasons = new HashMap<>();
            for (JsonNode node : root.path("items")) {
                reasons.put(node.path("name").asText(), node.path("reason").asText());
            }
            for (AiItemVO item : items) {
                String reason = reasons.get(item.getName());
                item.setReason(StringUtils.hasText(reason) ? reason : templateReason(item, intent));
            }
        } catch (Exception e) {
            log.warn("理由生成失败, 使用模板兜底: {}", e.getMessage());
            logCall(AiConstant.BIZ_REASON, null, false, e.getMessage());
            for (AiItemVO item : items) {
                item.setReason(templateReason(item, intent));
            }
        }
    }

    private String templateReason(AiItemVO item, AiIntent intent) {
        if (intent != null && intent.getSpicy() != null && intent.getSpicy() > 0) {
            return "口味对得上，价格也合适";
        }
        return "本店比较受欢迎的一道";
    }

    private String describeIntent(AiIntent intent) {
        StringBuilder sb = new StringBuilder();
        if (intent.getSpicy() != null) {
            sb.append(spicyName(intent.getSpicy())).append(' ');
        }
        if (intent.getFlavors() != null && !intent.getFlavors().isEmpty()) {
            sb.append(String.join("/", intent.getFlavors())).append(' ');
        }
        if (intent.getPeople() != null) {
            sb.append(intent.getPeople()).append("人 ");
        }
        return sb.toString().trim();
    }

    // ---------------- 默认 / 降级 ----------------

    private AiRecommendVO buildDefault(Long categoryId, String mode, String speech, String emotion) {
        List<AiItemVO> items = new ArrayList<>();
        try {
            Dish query = new Dish();
            query.setStatus(StatusConstant.ENABLE);
            if (categoryId != null) {
                query.setCategoryId(categoryId);
            }
            List<Dish> dishes = dishMapper.list(query);
            Map<String, Integer> rank = loadSalesRank();
            List<Dish> sorted = new ArrayList<>(dishes);
            sorted.sort((a, b) -> {
                Integer ra = rank.get(a.getName());
                Integer rb = rank.get(b.getName());
                int va = ra == null ? 999 : ra;
                int vb = rb == null ? 999 : rb;
                return Integer.compare(va, vb);
            });
            for (Dish dish : sorted) {
                if (items.size() >= maxItems) {
                    break;
                }
                if (dish.getStock() != null && dish.getStock() <= 0) {
                    continue;
                }
                items.add(AiItemVO.builder()
                        .dishId(dish.getId())
                        .name(dish.getName())
                        .price(dish.getPrice())
                        .image(dish.getImage())
                        .score(0)
                        .reason("本店热销")
                        .build());
            }
        } catch (Exception e) {
            log.warn("默认推荐查询失败: {}", e.getMessage());
        }
        return AiRecommendVO.builder()
                .mode(mode)
                .degraded(!AiConstant.MODE_DEFAULT.equals(mode))
                .needConfirm(true)
                .emotion(emotion)
                .speech(speech)
                .items(items)
                .build();
    }

    private AiRecommendVO buildDegradedByKeyword(String normalized, Long categoryId) {
        AiRecommendVO vo = buildDefault(categoryId, AiConstant.MODE_DEGRADED, degradedSpeech, AiConstant.EMOTION_CONFUSED);
        vo.setNeedConfirm(true);
        return vo;
    }

    // ---------------- 工具 ----------------

    private AiRecommendVO finish(AiRecommendVO vo, String cacheKey, AiIntent intent, long start) {
        vo.setElapsedMs(System.currentTimeMillis() - start);
        if (!Boolean.TRUE.equals(vo.getDegraded()) || AiConstant.MODE_DEFAULT.equals(vo.getMode())) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey,
                        objectMapper.writeValueAsString(vo), Duration.ofMinutes(cacheTtlMinutes));
            } catch (Exception e) {
                log.warn("推荐结果缓存写入失败: {}", e.getMessage());
            }
        }
        return vo;
    }

    private boolean isCircuitOpen() {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(AiConstant.CIRCUIT_KEY));
        } catch (Exception e) {
            return false;
        }
    }

    private void logCall(String bizType, LlmClient.LlmResult result, boolean success, String errorMsg) {
        try {
            AiCallLog record = AiCallLog.builder()
                    .bizType(bizType)
                    .model(result == null ? model : result.getModel())
                    .promptTokens(result == null ? 0 : result.getPromptTokens())
                    .completionTokens(result == null ? 0 : result.getCompletionTokens())
                    .costMs(result == null ? 0 : (int) result.getElapsedMs())
                    .degraded(success ? 0 : 1)
                    .success(success ? 1 : 0)
                    .errorMsg(errorMsg == null ? null : (errorMsg.length() > 250 ? errorMsg.substring(0, 250) : errorMsg))
                    .traceId(String.valueOf(System.currentTimeMillis()))
                    .createTime(LocalDateTime.now())
                    .build();
            aiCallLogMapper.insert(record);
        } catch (Exception e) {
            log.warn("AI 调用日志写入失败: {}", e.getMessage());
        }
    }

    private int upsert(long dishId, String type, String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        return dishTagMapper.upsert(DishTag.builder()
                .dishId(dishId)
                .tagType(type)
                .tagValue(value.length() > 32 ? value.substring(0, 32) : value)
                .createTime(LocalDateTime.now())
                .build());
    }

    private int upsertArray(long dishId, String type, JsonNode node) {
        int count = 0;
        for (JsonNode item : node) {
            count += upsert(dishId, type, item.asText());
        }
        return count;
    }

    private String spicyName(Integer spicy) {
        if (spicy == null) {
            return null;
        }
        switch (spicy) {
            case 0:
                return "不辣";
            case 1:
                return "微辣";
            case 2:
                return "中辣";
            case 3:
                return "特辣";
            default:
                return null;
        }
    }

    private String normalize(String query) {
        if (query == null) {
            return "";
        }
        String result = query.trim().toLowerCase();
        result = result.replaceAll("[\\p{Punct}\\s，。！？、；：“”‘’（）【】…—]+", "");
        return result;
    }

    private String toJsonString(String text) {
        try {
            return objectMapper.writeValueAsString(text == null ? "" : text);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    // ---------------- mock 内容（无 Key 时跑通全链路） ----------------

    private String mockIntent() {
        return "{\"spicy\":2,\"flavors\":[\"麻辣\"],\"excludeIngredients\":[],\"budgetPerPerson\":40,\"people\":2,\"category\":null,\"keyword\":null}";
    }

    private String mockReasons() {
        return "{\"items\":[]}";
    }

    private String mockTags() {
        return "{\"dishes\":[]}";
    }
}
