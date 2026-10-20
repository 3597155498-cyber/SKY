package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 自然语言点餐响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRecommendVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** AI / DEFAULT（没听懂或没说话）/ DEGRADED（模型不可用） */
    private String mode;

    private Boolean degraded;

    /** 是否需要用户确认（没听清时为 true） */
    private Boolean needConfirm;

    /** 情绪枚举，供前端桌宠映射表情 */
    private String emotion;

    /** 人格化话术（桌宠气泡内容） */
    private String speech;

    /** 解析出的结构化意图 */
    private AiIntent parsed;

    private List<AiItemVO> items;

    private Long elapsedMs;
}
