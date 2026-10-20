-- T10/T11: AI 能力（菜品标签 + 调用日志）

CREATE TABLE dish_tag (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  dish_id     BIGINT      NOT NULL,
  tag_type    VARCHAR(16) NOT NULL COMMENT 'SPICY/FLAVOR/INGREDIENT/PEOPLE/LIGHT',
  tag_value   VARCHAR(32) NOT NULL COMMENT '微辣/麻辣/牛肉/香菜/双人份',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dish_tag (dish_id, tag_type, tag_value),
  KEY idx_type_value (tag_type, tag_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品结构化标签';

CREATE TABLE ai_call_log (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  biz_type          VARCHAR(32)  NOT NULL COMMENT 'RECOMMEND_PARSE/RECOMMEND_REASON/DISH_TAG',
  model             VARCHAR(64)  NOT NULL,
  prompt_tokens     INT          NULL,
  completion_tokens INT          NULL,
  cost_ms           INT          NOT NULL,
  degraded          TINYINT      NOT NULL DEFAULT 0,
  success           TINYINT      NOT NULL DEFAULT 1,
  error_msg         VARCHAR(255) NULL,
  trace_id          VARCHAR(64)  NULL,
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_biz_time (biz_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大模型调用日志';