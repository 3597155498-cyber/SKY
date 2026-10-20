package com.sky.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 评价分析消息：只传评论 id，消费端回表拿内容，避免消息体随文本膨胀。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentAnalyzeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long commentId;

    private Long orderId;

    private Integer score;

    /**
     * 提交时间（epoch millis），仅用于排查消息积压，不参与业务判断
     */
    private Long submittedAt;
}
