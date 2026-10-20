package com.sky.mq;

import com.sky.service.CommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 评价批量分析消费者。
 * <p>
 * 批量消费 + 显式重试：整批重试 3 次（1s/2s 退避），仍失败则把该批消息逐条转发到
 * comment.dlx/comment.analyze.dlq，随后正常返回让原消息 ack，避免毒消息无限重投。
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "sky.mq.enabled", havingValue = "true", matchIfMissing = true)
public class CommentAnalyzeConsumer {

    @Autowired
    private CommentService commentService;

    @Autowired
    private RetryTemplate commentRetryTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = CommentRabbitConfig.COMMENT_QUEUE)
    public void onMessage(List<CommentAnalyzeMessage> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return;
        }
        List<Long> commentIds = new ArrayList<>(messages.size());
        try {
            int analyzed = commentRetryTemplate.execute(context -> {
                commentIds.clear();
                for (CommentAnalyzeMessage message : messages) {
                    if (message == null || message.getCommentId() == null || message.getCommentId() <= 0) {
                        throw new IllegalArgumentException("非法评价消息: " + message);
                    }
                    commentIds.add(message.getCommentId());
                }
                return commentService.analyzeByIds(commentIds);
            });
            log.info("MQ 批量分析完成, 消息={} 条, 实际分析={} 条, commentIds={}",
                    messages.size(), analyzed, commentIds);
        } catch (Exception e) {
            log.error("评价批量分析重试耗尽, 转死信队列, 消息={} 条, commentIds={}",
                    messages.size(), commentIds, e);
            for (CommentAnalyzeMessage message : messages) {
                if (message == null) {
                    continue;
                }
                try {
                    rabbitTemplate.convertAndSend(CommentRabbitConfig.COMMENT_DLX,
                            CommentRabbitConfig.COMMENT_DLQ_ROUTING_KEY, message);
                } catch (Exception ex) {
                    // 死信转发失败只能记录；原消息已 ack，需人工从日志捞取补偿
                    log.error("评价死信转发失败, commentId={}", message.getCommentId(), ex);
                }
            }
        }
    }
}
