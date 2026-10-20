-- T12: 评价 + 离线分析
CREATE TABLE comment (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  order_id    BIGINT       NOT NULL,
  user_id     BIGINT       NOT NULL,
  score       TINYINT      NOT NULL COMMENT '1-5 星',
  content     VARCHAR(500) NOT NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order (order_id),
  KEY idx_score_time (score, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单评价';

CREATE TABLE comment_analysis (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  comment_id  BIGINT      NOT NULL,
  sentiment   VARCHAR(16) NOT NULL COMMENT 'POSITIVE/NEUTRAL/NEGATIVE',
  themes      VARCHAR(200) NULL COMMENT '逗号分隔：配送/分量/口味/包装/服务/价格',
  analyzed_by VARCHAR(16) NOT NULL DEFAULT 'RULE' COMMENT 'RULE/LLM',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_comment (comment_id),
  KEY idx_sentiment (sentiment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价分析结果';