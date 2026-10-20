package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 菜品结构化标签
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DishTag implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long dishId;

    // SPICY / FLAVOR / INGREDIENT / PEOPLE / LIGHT
    private String tagType;

    // 微辣 / 麻辣 / 牛肉 / 香菜 / 双人份
    private String tagValue;

    private LocalDateTime createTime;
}
