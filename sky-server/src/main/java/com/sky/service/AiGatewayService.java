package com.sky.service;

import com.sky.dto.AiRecommendDTO;
import com.sky.vo.AiRecommendVO;

import java.util.Map;

/**
 * 主服务对 AI 能力的统一入口：内部走 OpenFeign，失败走本地降级。
 */
public interface AiGatewayService {

    AiRecommendVO recommend(AiRecommendDTO dto, Long userId);

    int refreshDishTags();

    /**
     * 评价周报建议；AI 服务不可用时返回 null，由调用方用模板兜底
     */
    String commentSummary(Map<String, Object> payload);

    Map<String, Object> status();
}
