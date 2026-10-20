package com.sky.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/**
 * 评价异步分析 MQ 拓扑。
 * <p>
 * comment.exchange --comment.analyze--> comment.analyze.queue
 * 失败重试 3 次（1s/2s 退避），仍失败 -> comment.dlx -> comment.analyze.dlq。
 */
@Configuration
@ConditionalOnProperty(name = "sky.mq.enabled", havingValue = "true", matchIfMissing = true)
public class CommentRabbitConfig {

    public static final String COMMENT_EXCHANGE = "comment.exchange";
    public static final String COMMENT_QUEUE = "comment.analyze.queue";
    public static final String COMMENT_ROUTING_KEY = "comment.analyze";

    public static final String COMMENT_DLX = "comment.dlx";
    public static final String COMMENT_DLQ = "comment.analyze.dlq";
    public static final String COMMENT_DLQ_ROUTING_KEY = "comment.analyze.dlq";

    @Value("${sky.mq.comment.batch-size:20}")
    private int batchSize;

    @Bean
    public DirectExchange commentExchange() {
        return new DirectExchange(COMMENT_EXCHANGE, true, false);
    }

    @Bean
    public Queue commentAnalyzeQueue() {
        return QueueBuilder.durable(COMMENT_QUEUE)
                .withArgument("x-dead-letter-exchange", COMMENT_DLX)
                .withArgument("x-dead-letter-routing-key", COMMENT_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding commentAnalyzeBinding() {
        return BindingBuilder.bind(commentAnalyzeQueue())
                .to(commentExchange())
                .with(COMMENT_ROUTING_KEY);
    }

    @Bean
    public DirectExchange commentDlx() {
        return new DirectExchange(COMMENT_DLX, true, false);
    }

    @Bean
    public Queue commentDlq() {
        return QueueBuilder.durable(COMMENT_DLQ).build();
    }

    @Bean
    public Binding commentDlqBinding() {
        return BindingBuilder.bind(commentDlq())
                .to(commentDlx())
                .with(COMMENT_DLQ_ROUTING_KEY);
    }

    /**
     * Jackson 序列化消息体，消费端可直接反序列化为 CommentAnalyzeMessage。
     */
    @Bean
    public MessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 批量消费：一次最多 20 条，1s 没攒满也会触发，兼顾吞吐与延迟。
     * <p>
     * 重试由消费者里的 RetryTemplate 显式完成（避免 advice 与批量监听器的组合差异），
     * 重试耗尽后由消费者显式转发到 comment.dlx，保证毒消息可观测、不无限重投。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jacksonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonMessageConverter);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(batchSize);
        factory.setReceiveTimeout(1000L);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public RetryTemplate commentRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(3)
                .exponentialBackoff(1000, 2.0, 5000)
                .build();
    }
}
