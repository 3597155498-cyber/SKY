package com.sky.task;

import com.sky.constant.OrderTimeoutConstant;
import com.sky.constant.StockBizType;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 超时未支付订单处理：Redis ZSet 延迟队列 + 秒级轮询
 * <p>
 * 相比"每分钟全表扫描 + 逐条 update"：
 * 1、只处理到期订单，扫描量与订单总量无关
 * 2、ZREM 的返回值决定归属，多实例部署时同一订单只会被一台机器处理
 * 3、状态更新带 status 条件，支付与超时并发时不会互相覆盖
 */
@Component
@Slf4j
public class OrderTimeoutTask {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private StockService stockService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 秒级轮询到期订单；fixedDelay 保证上一轮处理完才开始下一轮，避免任务重入
     */
    @Scheduled(fixedDelay = 1000)
    public void pollTimeoutOrders() {
        long now = System.currentTimeMillis();
        Set<String> orderIds = stringRedisTemplate.opsForZSet()
                .rangeByScore(OrderTimeoutConstant.TIMEOUT_ZSET_KEY, 0, now, 0, OrderTimeoutConstant.BATCH_SIZE);
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        for (String orderIdStr : orderIds) {
            // 抢占：只有 ZREM 成功（返回 1）的实例才处理，天然幂等
            Long removed = stringRedisTemplate.opsForZSet()
                    .remove(OrderTimeoutConstant.TIMEOUT_ZSET_KEY, orderIdStr);
            if (removed == null || removed == 0) {
                continue;
            }
            Long orderId = Long.valueOf(orderIdStr);
            try {
                // 条件更新：只有仍处于"待付款"的订单才会被取消
                int updated = orderMapper.cancelTimeoutOrder(orderId, "订单超时,自动取消", LocalDateTime.now());
                if (updated == 0) {
                    log.info("订单状态已变更, 跳过超时取消, orderId={}", orderId);
                    continue;
                }
                stockService.restoreForOrder(orderId, StockBizType.TIMEOUT);
                log.info("超时订单已取消并回补库存, orderId={}", orderId);
            } catch (Exception e) {
                // 单条失败不影响其余订单；重新放回队列由下一轮重试
                log.error("处理超时订单失败, orderId={}", orderId, e);
                stringRedisTemplate.opsForZSet()
                        .add(OrderTimeoutConstant.TIMEOUT_ZSET_KEY, orderIdStr, System.currentTimeMillis() + 10_000L);
            }
        }
    }
}
