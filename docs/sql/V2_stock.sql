-- T02: 菜品/套餐双库存 + 下单幂等 + 库存流水
-- 执行前已备份: docs/sql/backup/baseline.sql

ALTER TABLE dish    ADD COLUMN stock INT NOT NULL DEFAULT 0 COMMENT '库存' AFTER price;
ALTER TABLE setmeal ADD COLUMN stock INT NOT NULL DEFAULT 0 COMMENT '库存' AFTER price;
UPDATE dish SET stock = 100 WHERE stock = 0;
UPDATE setmeal SET stock = 100 WHERE stock = 0;

-- 下单幂等：幂等键 + 唯一索引兜底（并发重复提交只落一单）
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(64) NULL COMMENT '幂等键' AFTER number;
ALTER TABLE orders ADD UNIQUE KEY uk_user_idem (user_id, idempotency_key);

-- 状态+时间联合索引：超时订单扫描与报表共用
ALTER TABLE orders ADD KEY idx_status_order_time (status, order_time);

-- 库存流水：扣减/回补的唯一依据，同时承担回补幂等
CREATE TABLE stock_log (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  order_id        BIGINT      NOT NULL COMMENT '订单id',
  order_detail_id BIGINT      NULL COMMENT '订单明细id',
  target_type     VARCHAR(16) NOT NULL COMMENT 'DISH 菜品 / SETMEAL 套餐',
  target_id       BIGINT      NOT NULL COMMENT '菜品或套餐id',
  change_qty      INT         NOT NULL COMMENT '负数扣减，正数回补',
  biz_type        VARCHAR(16) NOT NULL COMMENT 'PLACE 下单 / CANCEL 取消 / TIMEOUT 超时 / REJECT 拒单',
  create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_once (order_id, target_type, target_id, biz_type),
  KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水';