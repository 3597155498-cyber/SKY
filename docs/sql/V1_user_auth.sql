-- T01: 用户端认证
-- openid 唯一，防止并发登录重复注册
ALTER TABLE user ADD UNIQUE KEY uk_openid (openid);
