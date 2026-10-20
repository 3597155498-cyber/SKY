package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 大模型调用日志：成本与降级可观测
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    // RECOMMEND_PARSE / RECOMMEND_REASON / DISH_TAG
    private String bizType;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer costMs;

    // 1 表示走了降级
    private Integer degraded;

    private Integer success;

    private String errorMsg;

    private String traceId;

    private LocalDateTime createTime;
}
