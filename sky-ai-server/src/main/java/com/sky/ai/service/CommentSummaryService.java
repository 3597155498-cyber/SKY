package com.sky.ai.service;

import java.util.Map;

/**
 * 评价周报建议：把主服务算好的统计结果交给模型生成一段可执行建议。
 */
public interface CommentSummaryService {

    Map<String, Object> summarize(Map<String, Object> payload);
}
