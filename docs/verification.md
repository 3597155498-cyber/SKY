# 验证记录

> 每条记录对应一张改造任务卡，命令可复跑。环境：Windows 10/11 + JDK 21 + Maven 3.9.16 + MySQL 8.0.43 + Redis（本机 6379/db1）。

## 环境自检（P4）

```bash
mvn -B -q -DskipTests package
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar
```

结果：

```
Started SkyApplication in 7.515 seconds (JVM running for 8.052)
POST /admin/employee/login -> {"code":1,...,"data":{"token":"eyJhbGciOiJIUzI1NiJ9..."}}
```

结论：Spring Boot 2.7.3（Spring 5.3.22）运行在 JDK 21 上启动正常，无兼容性问题。

---

## T01 用户端认证闭环

| # | 检查项 | 命令/方式 | 期望 | 实际 |
|---|---|---|---|---|
| 1 | body 传 code 登录 | `POST /user/user/login` `{"code":"test_code_001"}` | code=1，返回 token | ✅ code=1，userId=11，token 正常 |
| 2 | query 传 code 登录 | `POST /user/user/login?code=test_code_001` | 返回同一 userId | ✅ userId=11 |
| 3 | 带 token 查地址簿 | `GET /user/addressBook/list` + `authentication` | code=1，数据归属该用户 | ✅ count=1，返回记录 userId=11 |
| 4 | 无 token 访问 | `GET /user/addressBook/list` | 401 | ✅ 401 |
| 5 | admin token 访问用户端 | 同上，header 用管理端 token | 401 | ✅ 401（双端密钥隔离） |

补充：`ALTER TABLE user ADD UNIQUE KEY uk_openid (openid)` 已执行，并发登录不会重复注册。

---

## T02 双库存 + 幂等下单 + 库存流水

脚本：`docs/verify/t02-verify.ps1`（每次运行使用唯一幂等键前缀，互不干扰）

```
run=1020175031 users: u1=11 addr=10 ; u2=12 addr=11
V1  success=9/20 busy=0 distinctOrderIds=33 orders=1 dish46Stock=98 placeLogs=1
PASS  V1 orders created: 1
PASS  V1 all successes share one order id: 1
PASS  V1 stock deducted once: 98
PASS  V1 stock_log rows: 1
V1b code=1  orderId=33  orders=1
PASS  V1b replay returns same order: 33
PASS  V1b replay creates nothing: 1
PASS  V1b stock still 98: 98
V2  code=0  orders=0  dish47Stock=5  cartKept=1
PASS  V2 rejected: 0
PASS  V2 stock unchanged: 5
PASS  V2 no order: 0
PASS  V2 cart kept: 1
V3  codes=1,0  orders=1  dish47Stock=2
PASS  V3 exactly one success: 1
PASS  V3 one order: 1
PASS  V3 stock 5-3=2, no negative: 2
V4  code=0  setmeal32Stock=100  orders=0
PASS  V4 rejected by component dish: 0
PASS  V4 setmeal stock unchanged: 100
PASS  V4 no order: 0
V5  code=1  setmeal32Stock=99  dishLogs=8  setmealLogs=1
PASS  V5 setmeal order ok: 1
PASS  V5 setmeal stock 99: 99
PASS  V5 component dish logs: 8
PASS  V5 setmeal log: 1
V6  dish54 before=99 after=100  setmeal32 after=100  cancelLogs=9
PASS  V6 dish restored: 100
PASS  V6 setmeal restored: 100
PASS  V6 cancel logs: 9
---- done ----
```

覆盖场景：

- V1：20 并发同一幂等键 → 只落 1 单，成功响应共享同一 orderId，库存只扣一次
- V1b：订单提交后用同一幂等键重放 → 返回原订单，不重复下单（发现并修复"购物车校验早于幂等判断"的顺序缺陷）
- V2：库存 5 买 6 → 拒绝，库存不变，购物车保留
- V3：两用户并发抢库存 5（各买 3）→ 一成一败，末态 2，无负库存
- V4：套餐含售罄组成菜品 → 整单拒绝，套餐库存不变
- V5：套餐下单 → 套餐自身 −1、8 个组成菜品各 −1，流水 8 + 1 条
- V6：取消订单 → 9 项库存全部回补，CANCEL 流水 9 条

---

## T03 演示资产

| 检查项 | 期望 | 实际 |
|---|---|---|
| `docker compose config --quiet` | 语法通过，退出码 0 | ✅ 退出码 0 |
| `mvn package` 产物可启动 | 8080 可访问 | ✅ 与 P4 相同 |
| README 命令可执行 | 按文档能跑通 | ✅ 本地启动 / 数据库初始化命令已逐条执行 |
| 镜像构建与 `compose up` | 三容器可用 | ➖ 不在本次范围：编排文件已通过 `docker compose config` 语法校验，镜像构建验证经确认取消 |

交付物：`README.md`、`Dockerfile`、`docker-compose.yml`、`.dockerignore`、`.env.example`、`application-docker.yml`、`docs/sql/V1_user_auth.sql`（补 T01 的唯一索引）。

---

## T04 超时订单：Redis ZSet 延迟队列

脚本：`docs/verify/t04-verify.ps1`

```
PASS  V1 in timeout zset: True            （下单后自动入队，score = 下单时间 + 15min）
PASS  V1 score ~ now+15min: True
PASS  V2 order cancelled: 6               （模拟到期后 3 秒内被取消）
PASS  V2 stock restored: 100
PASS  V2 TIMEOUT stock_log: 1             （回补流水按 biz_type=TIMEOUT 记录）
PASS  V2 removed from zset: 0             （ZREM 抢占成功才处理）
PASS  V3 no duplicate restore: 1          （重复入队不会二次回补，uk_once 拦截）
PASS  V4 paid order not cancelled: 2      （已支付订单不会被超时任务误取消）
```

## T05 WebSocket 加固

脚本：`docs/verify/t05-verify.ps1`

```
PASS  V1 reminder type=2: True            （收到 {"orderId":42,"type":2,...}）
PASS  V2 broadcast after mass disconnect: True   （50 连接并发连/断后广播仍可用）
PASS  V3 reminder after client gone: 1    （客户端消失后服务端无异常，死连接被清理）
```

## T06 手写缓存层

脚本：`docs/verify/t06-verify.ps1`

```
PASS  V1 second call hits cache (< first): True
PASS  V2 empty result cached: True
PASS  V3 single rebuild, not 50x: True     （50 并发冷 key，总查询 63 vs 无缓存 450）
PASS  V4 ttl jitter present: True          （4 个 key 的 TTL 跨 420 秒）
PASS  V5 cache not evicted by ordering: 1  （下单 5 次后缓存仍在，库存实时反映）
PASS  V6 setmeal cache cleared / dish cache untouched: True（精准失效）
```

## T07 报表聚合与索引

脚本：`docs/verify/t07-verify.ps1`

```
PASS  V1 no per-day query (<= 15): True    （30 天三类统计共 8 条 SQL，旧实现约 90 条）
PASS  V2 export xlsx magic bytes (PK): True（导出 10 KB 的 xlsx）
PASS  V3 orders/dish/user 索引存在
```

说明：报表模板行数固定 30 行，**未引入 SXSSF 流式导出**——没有大数据量场景，硬上属于为技术而技术。本卡的真实瓶颈是"按天循环查库"，已用一次 GROUP BY 聚合替代。

## T08 测试

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
- OrderStatusTransferTest：合法/非法状态流转（纯单元测试）
- CacheClientTest：缓存命中后回源只执行一次（@SpringBootTest RANDOM_PORT）
```

注意：`@SpringBootTest` 默认 MOCK 环境会因 `ServerEndpointExporter` 报 `ServerContainer not available`，需用 `webEnvironment = RANDOM_PORT`。

## T09 订单状态机

- 新增 `OrderStatusTransfer`（1→2/6，2→3/6，3→4/6，4→5/6，终态不可流转）
- 接单/拒单/取消/派送/完成/用户取消 全部改为条件更新 `where id=? and status=fromStatus`
- SQL 层并发语义验证：

```
first_update_rows=1
second_update_rows=0
final_status=3
```

- 单元测试：`OrderStatusTransferTest` 全绿

HTTP 级并发验证（修正客户端后）：

```
round0: confirmCode=1 cancelCode= finalStatus=3
T09 rounds=10 illegalFinalStatus=0 bothSucceeded=0
PASS  T09 final status always legal: 0
PASS  T09 only one side wins per round: 0
PASS  T09 duplicate confirm rejected: 0
---- t09 done ----
```

> 排查记录：此前 `PUT /admin/order/confirm` 返回 400，**不是服务端缺陷**。
> PowerShell 调原生 `curl.exe` 时会把 `--data '{"id":99}'` 的引号吃掉，实际发出 `{id:99}`，
> 服务端 Jackson 报 `Unexpected character ('i') ... column 3`。
> 过程中补充了一处真实改进：`GlobalExceptionHandler` 原本不处理
> `HttpMessageNotReadableException`，请求体畸形时只回一个空 400；现已统一返回 `{"code":0,"msg":"请求参数格式错误"}`。
> 教训：验证脚本里的 HTTP 客户端本身也要可信，用 `Invoke-RestMethod` 或
> `[System.Net.Http.StringContent]::new(...)`，不要用 `New-Object StringContent(...)`。