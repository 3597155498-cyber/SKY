package com.sky.task;

import com.sky.constant.StockBizType;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class OrderTask {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StockService stockService;

    /*
     * 处理超时订单
     * 注：T04 将改为 Redis ZSet 延迟队列，此处暂时保留并补上库存回补
     * */
    @Scheduled(cron = "0 *  * * * ?")
    public void processTimeoutOrder() {
        log.info("定时处理超时订单: {}", LocalDateTime.now());

        LocalDateTime time = LocalDateTime.now().plusMinutes(-15);   //当前时间减少15分钟

        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.PENDING_PAYMENT, time);

        if (ordersList != null && ordersList.size() > 0) {

            for (Orders orders : ordersList) {
                orders.setStatus(Orders.CANCELLED);
                orders.setCancelReason("订单超时,自动取消");
                orders.setCancelTime(LocalDateTime.now());
                orderMapper.update(orders);
                // 超时取消后回补库存（依赖 stock_log 唯一键保证幂等）
                stockService.restoreForOrder(orders.getId(), StockBizType.TIMEOUT);
            }
        }
    }

    /*
     * 处理一直处于派送中的订单
     * */
    @Scheduled(cron = "0 0 1  * * ?")
    public void processDeliveryOrder() {
        log.info("的定时处理处于派送中的订单: {}", LocalDateTime.now());
        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);

        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, time);

        if (ordersList != null && ordersList.size() > 0) {
            for (Orders orders : ordersList) {
                orders.setStatus(Orders.COMPLETED);
                orderMapper.update(orders);
            }
        }
    }
}
