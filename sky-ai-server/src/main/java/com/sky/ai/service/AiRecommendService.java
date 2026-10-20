package com.sky.ai.service;

import com.sky.dto.AiRecommendDTO;
import com.sky.vo.AiRecommendVO;

import java.util.Map;

public interface AiRecommendService {

    /**
     * 自然语言点餐推荐
     *
     * @param dto    请求
     * @param userId 当前登录用户
     */
    AiRecommendVO recommend(AiRecommendDTO dto, Long userId);

    /**
     * 刷新菜品标签（离线批处理，可重跑）
     *
     * @return 打标数量
     */
    int refreshDishTags();

    /**
     * AI 状态：模型、熔断开关、今日调用与 token
     */
    Map<String, Object> status();
}
