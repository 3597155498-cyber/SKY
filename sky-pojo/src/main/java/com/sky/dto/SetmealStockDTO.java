package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 套餐可售量：套餐自身库存与组成菜品可支撑份数取小
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetmealStockDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    //套餐自身库存
    private Integer stock;

    //组成菜品可支撑的套餐份数
    private Integer componentStock;
}
