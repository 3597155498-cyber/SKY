-- T07: 报表统计索引（聚合查询与按天分组）
-- orders 已有 idx_status_order_time(status, order_time)，按时间范围聚合可命中
ALTER TABLE order_detail ADD KEY idx_order_id (order_id);
ALTER TABLE user ADD KEY idx_create_time (create_time);