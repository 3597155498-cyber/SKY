package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 自然语言点餐请求
 */
@Data
public class AiRecommendDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户原话，可能来自键盘输入，也可能来自语音识别（含错别字、语气词、断句）
     */
    private String query;

    /**
     * 可选：限定分类
     */
    private Long categoryId;
}
