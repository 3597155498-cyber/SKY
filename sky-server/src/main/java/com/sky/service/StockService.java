package com.sky.service;

import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;

import java.util.List;

public interface StockService {

    /**
     * 下单扣减库存：散点菜品 + 套餐自身限量 + 套餐展开的组成菜品。
     * 必须在调用方事务内执行，任一项不足则整单回滚。
     *
     * @param order            已落库的订单（需要 orderId）
     * @param shoppingCartList 购物车数据
     */
    void deductForOrder(Orders order, List<ShoppingCart> shoppingCartList);

    /**
     * 按库存流水回补库存，依赖 stock_log 唯一键保证幂等
     *
     * @param orderId 订单id
     * @param bizType 业务类型（CANCEL / TIMEOUT / REJECT）
     */
    void restoreForOrder(Long orderId, String bizType);

    /**
     * 判断套餐在指定数量下是否可售（套餐自身库存 + 组成菜品库存）
     */
    boolean isSetmealSellable(Long setmealId, int quantity);
}