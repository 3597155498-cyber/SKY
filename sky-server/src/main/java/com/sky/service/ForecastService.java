package com.sky.service;

import java.util.Map;

public interface ForecastService {

    /**
     * 菜品次日销量预测（非 LLM：7 日移动平均 × 星期系数）
     *
     * @param days 历史窗口天数（默认 28）
     * @param top 返回前 N 个菜品
     */
    Map<String, Object> forecast(int days, int top);
}
