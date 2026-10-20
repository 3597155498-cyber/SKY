package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 提交评价
 */
@Data
public class CommentDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;

    // 1-5 星
    private Integer score;

    private String content;
}
