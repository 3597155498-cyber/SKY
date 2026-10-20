package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评价分析结果（词表规则产出，可回溯）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentAnalysis implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long commentId;

    // POSITIVE / NEUTRAL / NEGATIVE
    private String sentiment;

    // 逗号分隔：配送/分量/口味/包装/服务/价格
    private String themes;

    // RULE / LLM
    private String analyzedBy;

    private LocalDateTime createTime;
}
