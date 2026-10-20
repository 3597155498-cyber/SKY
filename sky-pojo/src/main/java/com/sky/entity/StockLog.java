package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存流水：扣减与回补的唯一依据，同时承担回补幂等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockLog implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    //订单id
    private Long orderId;

    //订单明细id
    private Long orderDetailId;

    //DISH 菜品 / SETMEAL 套餐
    private String targetType;

    //菜品或套餐id
    private Long targetId;

    //负数扣减，正数回补
    private Integer changeQty;

    //PLACE 下单 / CANCEL 取消 / TIMEOUT 超时 / REJECT 拒单
    private String bizType;

    private LocalDateTime createTime;
}