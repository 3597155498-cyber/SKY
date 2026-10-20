package com.sky.service;

import com.sky.dto.CommentDTO;
import com.sky.result.PageResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface CommentService {

    /**
     * 用户提交评价（一单一评）
     */
    void submit(CommentDTO commentDTO, Long userId);

    /**
     * 批量分析未处理评价（离线任务，可重跑）
     *
     * @return 本次分析条数
     */
    int analyzePending(int limit);

    /**
     * 按评论 id 批量分析（MQ 消费者调用；重复消费由 comment_analysis 唯一键保证幂等）
     *
     * @return 实际分析条数
     */
    int analyzeByIds(List<Long> commentIds);

    /**
     * 商家洞察：差评主题 TopN + 可行动建议
     */
    Map<String, Object> insight(LocalDate begin, LocalDate end);

    /**
     * 管理端评价分页
     */
    PageResult page(int page, int pageSize, Integer score);

    /**
     * 主动失效洞察缓存（数据导入/重算后必须调用，否则会读到旧结果）
     *
     * @return 删除的 key 数量
     */
    long clearInsightCache();
}
