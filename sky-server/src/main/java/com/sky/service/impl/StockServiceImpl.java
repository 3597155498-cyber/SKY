package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.constant.StockBizType;
import com.sky.constant.StockTargetType;
import com.sky.entity.Orders;
import com.sky.entity.SetmealDish;
import com.sky.entity.ShoppingCart;
import com.sky.entity.StockLog;
import com.sky.exception.StockNotEnoughException;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.StockMapper;
import com.sky.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@Slf4j
public class StockServiceImpl implements StockService {

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void deductForOrder(Orders order, List<ShoppingCart> shoppingCartList) {
        // TreeMap 天然按 id 升序，多订单并发时以固定顺序加锁，避免交叉扣减形成环路死锁
        Map<Long, Integer> dishQty = new TreeMap<>();
        Map<Long, Integer> setmealQty = new TreeMap<>();

        for (ShoppingCart cart : shoppingCartList) {
            int number = cart.getNumber() == null ? 0 : cart.getNumber();
            if (number <= 0) {
                continue;
            }
            if (cart.getDishId() != null) {
                dishQty.merge(cart.getDishId(), number, Integer::sum);
            }
            if (cart.getSetmealId() != null) {
                setmealQty.merge(cart.getSetmealId(), number, Integer::sum);
                // 套餐展开成组成菜品：份数 * 每份含的菜品数量
                for (SetmealDish setmealDish : setmealDishMapper.getBySetmealId(cart.getSetmealId())) {
                    int copies = setmealDish.getCopies() == null ? 1 : setmealDish.getCopies();
                    dishQty.merge(setmealDish.getDishId(), copies * number, Integer::sum);
                }
            }
        }

        // 1、扣菜品（含套餐展开部分）
        for (Map.Entry<Long, Integer> entry : dishQty.entrySet()) {
            if (stockMapper.deductDishStock(entry.getKey(), entry.getValue()) == 0) {
                log.warn("菜品库存不足, orderId={}, dishId={}, qty={}", order.getId(), entry.getKey(), entry.getValue());
                throw new StockNotEnoughException(MessageConstant.STOCK_NOT_ENOUGH);
            }
        }
        // 2、扣套餐自身限量
        for (Map.Entry<Long, Integer> entry : setmealQty.entrySet()) {
            if (stockMapper.deductSetmealStock(entry.getKey(), entry.getValue()) == 0) {
                log.warn("套餐库存不足, orderId={}, setmealId={}, qty={}", order.getId(), entry.getKey(), entry.getValue());
                throw new StockNotEnoughException(MessageConstant.STOCK_NOT_ENOUGH);
            }
        }
        // 3、记录流水（负向）
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<Long, Integer> entry : dishQty.entrySet()) {
            stockMapper.insert(StockLog.builder()
                    .orderId(order.getId())
                    .targetType(StockTargetType.DISH)
                    .targetId(entry.getKey())
                    .changeQty(-entry.getValue())
                    .bizType(StockBizType.PLACE)
                    .createTime(now)
                    .build());
        }
        for (Map.Entry<Long, Integer> entry : setmealQty.entrySet()) {
            stockMapper.insert(StockLog.builder()
                    .orderId(order.getId())
                    .targetType(StockTargetType.SETMEAL)
                    .targetId(entry.getKey())
                    .changeQty(-entry.getValue())
                    .bizType(StockBizType.PLACE)
                    .createTime(now)
                    .build());
        }
        log.info("扣减库存成功, orderId={}, 菜品{}项, 套餐{}项", order.getId(), dishQty.size(), setmealQty.size());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void restoreForOrder(Long orderId, String bizType) {
        List<StockLog> placeLogs = stockMapper.listByOrderAndBiz(orderId, StockBizType.PLACE);
        if (placeLogs == null || placeLogs.isEmpty()) {
            log.warn("订单无扣减流水, 跳过回补, orderId={}", orderId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (StockLog placeLog : placeLogs) {
            int qty = -placeLog.getChangeQty();
            StockLog restoreLog = StockLog.builder()
                    .orderId(orderId)
                    .targetType(placeLog.getTargetType())
                    .targetId(placeLog.getTargetId())
                    .changeQty(qty)
                    .bizType(bizType)
                    .createTime(now)
                    .build();
            try {
                stockMapper.insert(restoreLog);
            } catch (DuplicateKeyException e) {
                // uk_once 拦截重复回补
                log.info("库存已回补过, 跳过, orderId={}, target={}:{}", orderId, placeLog.getTargetType(), placeLog.getTargetId());
                continue;
            }
            if (StockTargetType.DISH.equals(placeLog.getTargetType())) {
                stockMapper.restoreDishStock(placeLog.getTargetId(), qty);
            } else {
                stockMapper.restoreSetmealStock(placeLog.getTargetId(), qty);
            }
        }
        log.info("库存回补完成, orderId={}, bizType={}", orderId, bizType);
    }

    @Override
    public boolean isSetmealSellable(Long setmealId, int quantity) {
        Integer setmealStock = stockMapper.getSetmealStock(setmealId);
        if (setmealStock == null || setmealStock < quantity) {
            return false;
        }
        List<SetmealDish> dishes = setmealDishMapper.getBySetmealId(setmealId);
        if (dishes == null || dishes.isEmpty()) {
            return false;
        }
        for (SetmealDish setmealDish : dishes) {
            int copies = setmealDish.getCopies() == null ? 1 : setmealDish.getCopies();
            Integer dishStock = stockMapper.getDishStock(setmealDish.getDishId());
            if (dishStock == null || dishStock < copies * quantity) {
                return false;
            }
        }
        return true;
    }
}