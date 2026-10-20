package com.sky.mq;

import com.sky.entity.Comment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 评价消息生产者。
 * <p>
 * 发布失败不反向影响评价落库：评论已经保存，管理员可调 /admin/comment/analyze 补偿；
 * 发布成功但 broker 未确认时通过 confirm 回调记录，便于排查。
 */
@Component
@Slf4j
public class CommentMessageProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void initCallbacks() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("评价消息未被 broker 确认, correlationId={}, cause={}",
                        correlationData == null ? null : correlationData.getId(), cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "评价消息路由失败, exchange={}, routingKey={}, replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
    }

    public void publish(Comment comment) {
        if (comment == null || comment.getId() == null) {
            return;
        }
        CommentAnalyzeMessage message = CommentAnalyzeMessage.builder()
                .commentId(comment.getId())
                .orderId(comment.getOrderId())
                .score(comment.getScore())
                .submittedAt(System.currentTimeMillis())
                .build();
        CorrelationData correlationData = new CorrelationData("comment-" + comment.getId());
        try {
            rabbitTemplate.convertAndSend(CommentRabbitConfig.COMMENT_EXCHANGE,
                    CommentRabbitConfig.COMMENT_ROUTING_KEY, message, correlationData);
            log.info("评价消息已投递, commentId={}, orderId={}", comment.getId(), comment.getOrderId());
        } catch (Exception e) {
            log.error("评价消息投递失败, commentId={}, 评价已保存，可用 /admin/comment/analyze 补偿",
                    comment.getId(), e);
        }
    }
}
