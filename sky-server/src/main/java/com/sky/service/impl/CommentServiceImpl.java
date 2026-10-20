package com.sky.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.entity.Comment;
import com.sky.entity.CommentAnalysis;
import com.sky.entity.Orders;
import com.sky.exception.BaseException;
import com.sky.mapper.CommentAnalysisMapper;
import com.sky.mapper.CommentMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mq.CommentMessageProducer;
import com.sky.result.PageResult;
import com.sky.service.AiGatewayService;
import com.sky.service.CommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评价与商家洞察
 * <p>
 * 设计取舍：主题与情感用词表规则（0 成本、可解释、可回溯），
 * 只有"周报建议"这一步调用一次模型（每次约 1 分钱，按天缓存）。
 */
@Service
@Slf4j
public class CommentServiceImpl implements CommentService {

    /**
     * 主题词表：命中即归类，可随时扩充
     */
    private static final Map<String, String[]> THEME_WORDS = new LinkedHashMap<>();
    private static final String[] NEGATIVE_WORDS = {
            "慢", "难吃", "太咸", "太淡", "腥", "油腻", "太少", "不够", "贵", "漏", "洒", "差",
            "不新鲜", "凉了", "迟到", "等了好久", "态度不好", "失望", "退钱"};
    private static final String[] POSITIVE_WORDS = {
            "好吃", "不错", "满意", "很快", "新鲜", "热情", "划算", "推荐", "喜欢", "赞", "入味"};

    static {
        THEME_WORDS.put("配送", new String[]{"配送", "送餐", "骑手", "送达", "迟到", "等了好久", "速度", "慢"});
        THEME_WORDS.put("分量", new String[]{"分量", "份量", "量少", "不够吃", "太少", "量足", "实在"});
        THEME_WORDS.put("口味", new String[]{"口味", "味道", "咸", "淡", "辣", "难吃", "好吃", "香", "腥", "油腻", "入味"});
        THEME_WORDS.put("包装", new String[]{"包装", "打包", "洒了", "漏", "盒子", "餐具", "凉了"});
        THEME_WORDS.put("服务", new String[]{"服务", "态度", "老板", "客服", "热情", "冷脸"});
        THEME_WORDS.put("价格", new String[]{"贵", "便宜", "性价比", "价格", "划算"});
    }

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private CommentAnalysisMapper commentAnalysisMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private AiGatewayService aiGatewayService;
    @Autowired
    private CommentMessageProducer commentMessageProducer;

    @Autowired
    private com.sky.cache.CacheClient cacheClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void submit(com.sky.dto.CommentDTO commentDTO, Long userId) {
        if (commentDTO == null || commentDTO.getOrderId() == null) {
            throw new BaseException("订单不能为空");
        }
        if (commentDTO.getScore() == null || commentDTO.getScore() < 1 || commentDTO.getScore() > 5) {
            throw new BaseException("评分必须在 1-5 之间");
        }
        if (!StringUtils.hasText(commentDTO.getContent())) {
            throw new BaseException("评价内容不能为空");
        }
        if (commentDTO.getContent().length() > 500) {
            throw new BaseException("评价内容不能超过 500 字");
        }
        Orders order = orderMapper.getByIdAndUserId(commentDTO.getOrderId(), userId);
        if (order == null) {
            throw new BaseException("订单不存在");
        }
        if (!Orders.COMPLETED.equals(order.getStatus())) {
            throw new BaseException("订单完成后才能评价");
        }
        if (commentMapper.getByOrderId(commentDTO.getOrderId()) != null) {
            throw new BaseException("该订单已评价");
        }
        Comment comment = Comment.builder()
                .orderId(commentDTO.getOrderId())
                .userId(userId)
                .score(commentDTO.getScore())
                .content(commentDTO.getContent().trim())
                .createTime(LocalDateTime.now())
                .build();
        commentMapper.insert(comment);
        commentMessageProducer.publish(comment);
        log.info("用户评价提交成功并投递异步分析, userId={}, orderId={}, commentId={}, score={}",
                userId, commentDTO.getOrderId(), comment.getId(), commentDTO.getScore());
    }

    @Override
    public int analyzePending(int limit) {
        List<Comment> pending = commentMapper.listUnanalyzed(limit);
        if (CollectionUtils.isEmpty(pending)) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Comment comment : pending) {
            analyze(comment, now);
        }
        log.info("评价分析完成, 本次分析 {} 条, 剩余 {} 条",
                pending.size(), Math.max(0, commentMapper.countAll() - analyzedCount()));
        return pending.size();
    }

    @Override
    public int analyzeByIds(List<Long> commentIds) {
        if (CollectionUtils.isEmpty(commentIds)) {
            return 0;
        }
        List<Comment> comments = commentMapper.listByIds(commentIds);
        if (CollectionUtils.isEmpty(comments)) {
            // 消息指向不存在的评价：按毒消息处理，让 Retry/DLX 接管，避免静默丢失
            log.warn("MQ 批量分析未找到评价, commentIds={}", commentIds);
            throw new IllegalStateException("评价不存在: " + commentIds);
        }
        LocalDateTime now = LocalDateTime.now();
        for (Comment comment : comments) {
            analyze(comment, now);
        }
        log.info("MQ 批量分析落库完成, 请求 {} 条, 实际 {} 条", commentIds.size(), comments.size());
        return comments.size();
    }

    private void analyze(Comment comment, LocalDateTime now) {
        String themes = extractThemes(comment.getContent());
        String sentiment = detectSentiment(comment.getScore(), comment.getContent());
        commentAnalysisMapper.upsert(CommentAnalysis.builder()
                .commentId(comment.getId())
                .sentiment(sentiment)
                .themes(themes)
                .analyzedBy("RULE")
                .createTime(now)
                .build());
    }

    @Override
    public Map<String, Object> insight(LocalDate begin, LocalDate end) {
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);
        String cacheKey = "ai:insight:" + begin + ":" + end;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            try {
                return objectMapper.readValue(cached, Map.class);
            } catch (Exception e) {
                stringRedisTemplate.delete(cacheKey);
            }
        }

        List<Comment> comments = commentMapper.listByRange(beginTime, endTime);
        List<CommentAnalysis> analyses = commentAnalysisMapper.listByCommentRange(beginTime, endTime);
        Map<Long, CommentAnalysis> analysisMap = new HashMap<>();
        for (CommentAnalysis analysis : analyses) {
            analysisMap.put(analysis.getCommentId(), analysis);
        }

        int total = comments.size();
        int negative = 0;
        int scoreSum = 0;
        Map<String, Integer> themeCount = new LinkedHashMap<>();
        for (Comment comment : comments) {
            scoreSum += comment.getScore() == null ? 0 : comment.getScore();
            CommentAnalysis analysis = analysisMap.get(comment.getId());
            String sentiment = analysis == null
                    ? detectSentiment(comment.getScore(), comment.getContent())
                    : analysis.getSentiment();
            if ("NEGATIVE".equals(sentiment)) {
                negative++;
                String themes = analysis == null ? extractThemes(comment.getContent()) : analysis.getThemes();
                for (String theme : splitThemes(themes)) {
                    Integer old = themeCount.get(theme);
                    themeCount.put(theme, old == null ? 1 : old + 1);
                }
            }
        }

        final int negativeTotal = negative;
        List<Map<String, Object>> topThemes = new ArrayList<>();
        themeCount.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("theme", entry.getKey());
                    item.put("count", entry.getValue());
                    item.put("ratio", negativeTotal == 0 ? 0.0
                            : Math.round(10000.0 * entry.getValue() / negativeTotal) / 100.0);
                    topThemes.add(item);
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("begin", begin.toString());
        result.put("end", end.toString());
        result.put("totalComments", total);
        result.put("avgScore", total == 0 ? 0.0 : Math.round(100.0 * scoreSum / total) / 100.0);
        result.put("negativeCount", negative);
        result.put("negativeRate", total == 0 ? 0.0 : Math.round(10000.0 * negative / total) / 100.0);
        result.put("topThemes", topThemes);
        result.put("summary", buildSummary(total, negative, topThemes));
        result.put("analyzed", analyses.size());
        result.put("trend", commentMapper.dailyTrend(beginTime, endTime));

        try {
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result),
                    Duration.ofMinutes(10));
        } catch (Exception e) {
            log.warn("洞察结果缓存失败: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public PageResult page(int page, int pageSize, Integer score) {
        PageHelper.startPage(page, pageSize);
        Page<Comment> result = commentMapper.pageQuery(score, null, null);
        return new PageResult(result.getTotal(), result.getResult());
    }

    @Override
    public long clearInsightCache() {
        long removed = cacheClient.evictByPattern("ai:insight:*");
        log.info("洞察缓存已主动失效, 删除 {} 个 key", removed);
        return removed;
    }

    // ---------------- 规则分析 ----------------

    private String extractThemes(String content) {
        if (!StringUtils.hasText(content)) {
            return "其他";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> entry : THEME_WORDS.entrySet()) {
            for (String word : entry.getValue()) {
                if (content.contains(word)) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(entry.getKey());
                    break;
                }
            }
        }
        return sb.length() == 0 ? "其他" : sb.toString();
    }

    private String detectSentiment(Integer score, String content) {
        if (score != null) {
            if (score <= 2) {
                return "NEGATIVE";
            }
            if (score >= 4) {
                return "POSITIVE";
            }
        }
        if (StringUtils.hasText(content)) {
            for (String word : NEGATIVE_WORDS) {
                if (content.contains(word)) {
                    return "NEGATIVE";
                }
            }
            for (String word : POSITIVE_WORDS) {
                if (content.contains(word)) {
                    return "POSITIVE";
                }
            }
        }
        return "NEUTRAL";
    }

    private List<String> splitThemes(String themes) {
        List<String> list = new ArrayList<>();
        if (!StringUtils.hasText(themes)) {
            return list;
        }
        for (String theme : themes.split(",")) {
            if (StringUtils.hasText(theme)) {
                list.add(theme.trim());
            }
        }
        return list;
    }

    private int analyzedCount() {
        return commentAnalysisMapper.listByCommentRange(
                LocalDateTime.now().minusYears(10), LocalDateTime.now()).size();
    }

    /**
     * 周报建议：唯一一次模型调用；失败退化为模板文案
     */
    private String buildSummary(int total, int negative, List<Map<String, Object>> topThemes) {
        String template = templateSummary(total, negative, topThemes);
        if (total == 0) {
            return template;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("totalComments", total);
            payload.put("negativeCount", negative);
            payload.put("topNegativeThemes", topThemes);
            String summary = aiGatewayService.commentSummary(payload);
            return StringUtils.hasText(summary) ? summary : template;
        } catch (Exception e) {
            log.warn("周报建议生成失败, 使用模板: {}", e.getMessage());
            return template;
        }
    }

    private String templateSummary(int total, int negative, List<Map<String, Object>> topThemes) {
        if (total == 0) {
            return "本期没有评价数据。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本期共 ").append(total).append(" 条评价，差评 ").append(negative).append(" 条");
        if (!topThemes.isEmpty()) {
            sb.append("，主要集中在「").append(topThemes.get(0).get("theme")).append("」");
        }
        sb.append("。");
        return sb.toString();
    }

}
