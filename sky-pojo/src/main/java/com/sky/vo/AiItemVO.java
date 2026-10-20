package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 单条推荐结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long dishId;

    private String name;

    private BigDecimal price;

    private String image;

    /** 推荐理由（模型生成或模板兜底） */
    private String reason;

    /** 打分，便于排查排序 */
    private Integer score;
}
