# 验证记录

> 每条记录对应一张改造任务卡，命令可复跑。环境：Windows 10/11 + JDK 21 + Maven 3.9.16 + MySQL 8.0.43 + Redis（本机 6379/db1）+ Nacos 2.3.2（T14 起）+ RabbitMQ 3.12.1（T15 起，WSL）。

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
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
- OrderStatusTransferTest：合法/非法状态流转（纯单元测试）
- CacheClientTest：缓存命中后回源只执行一次（@SpringBootTest RANDOM_PORT）
- HttpClientTest / SpringDateRedisTest：随机端口启动、HTTP 与 Redis 连通性
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
---

## T10/T11 AI 能力

### 环境与模型

- 模型：DeepSeek `deepseek-chat`（OpenAI 兼容 `/chat/completions`），Key 走环境变量 `DEEPSEEK_API_KEY`
- 注意：`Start-Process` 启动的 java 进程**不继承**用户级环境变量，启动时需在当前会话显式 `$env:DEEPSEEK_API_KEY=...`

### 接口

| 接口 | 说明 |
|---|---|
| `POST /user/ai/recommendSync` | 同步推荐（验证脚本与 Knife4j 使用） |
| `POST /user/ai/recommend` | SSE 事件流（事件级，非 token 级） |
| `GET /user/ai/status` | 模型、熔断、今日调用与 token |
| `POST /admin/ai/tag/refresh` | 菜品打标（离线批处理，可重跑） |

### 打标结果

24 道菜 → 80 条标签（SPICY/FLAVOR/INGREDIENT）。人工抽查 24 条，辣度 19/24 正确；
主要偏差是「酸菜」类被标成 `酸甜`（应为酸辣），对推荐排序影响有限（辣度维度可兜住）。

### 意图解析评测（200 条：170 标准 + 30 脏输入）

```
整体准确率(4字段全对): 95.5%
  标准集         : 100%
  脏输入集       : 70%
字段级(全体)    : spicy=96%  people=100%  budget=100%  exclude=99.5%
字段级(脏输入)  : spicy=73.33%  people=100%  budget=100%  exclude=96.67%
```

脏输入失败样本人工复核后，多数属于**标注口径问题而非模型错误**：

- `"嗯 就是 想吃辣的 不要聪"` → 模型给 spicy=2，标注写 null（标注错）；同时正确纠正「聪→葱」
- `"我要个那个水煮鱼 两个人"` → 模型给 spicy=2，标注写 null（水煮类推为辣是合理的）
- `"不迟香菜 来点轻淡的"` → 模型给 flavors=[清淡]，标注期望 spicy=0（`清淡→辣度0` 的映射本身有歧义）
- 模型还正确完成了「香采→香菜」「六十→60」「两各人→2人」的纠错

### 性能

- 单次推荐：解析 ~2.5s + 理由 ~2.5s ≈ 5s（真实模型）
- 超时策略：socket 6s、慢失败不重试（>2s 不重试），超时即降级，接口不 500
- 空输入：`mode=DEFAULT`、15ms、0 成本
- 成本：`ai_call_log` 记录 token；评测 200 条约 20 万 token

### 踩过的坑（验证脚本侧，非服务端缺陷）

1. PowerShell 5.1 `Invoke-RestMethod -Body '字符串'` 会用非 UTF-8 编码发送，中文被丢 → 必须传 UTF-8 字节
2. `Invoke-RestMethod` 解析响应时按 ANSI 解码，中文变乱码 → 用 `Invoke-WebRequest` + `RawContentStream` 自行 UTF-8 解码
3. 脚本文件必须带 **UTF-8 BOM**，否则 PS 5.1 按 ANSI 读导致中文语法错误
### 性能排查记录（从 14s 降到 0.8s）

| 现象 | 根因 | 修复 |
|---|---|---|
| 页面显示 14266ms | 每次调用都 `HttpClients.createDefault()` 新建客户端，重复 TLS 握手 | 改为共享连接池 |
| — | 额外一次"生成推荐理由"的模型调用（+2~7s） | `reason-enabled=false`，理由用模板 |
| — | 慢失败还会重试一次，延迟翻倍 | 不重试，失败即降级 |

实测：首次 5344ms（进程内第一次，含 TLS/类加载），后续 835ms，缓存 23ms。

踩坑：`new DefaultProxyRoutePlanner(null)` 会让应用启动失败（构造函数不接受 null），已回退。
---

## T12 商家洞察

### 数据规模

- 600 个已完成订单 + 600 条评价（近 30 天），按真实骑手/门店场景的分布构造：
  - 星级：1 星 91、2 星 179、3 星 30、4 星 150、5 星 150
  - 情感：NEGATIVE 277、NEUTRAL 23、POSITIVE 300
- 差评主题分布（故意做成"配送占大头"而不是平均撒）：配送 110、分量 64、包装 48、口味 45、价格 25、服务 15

### 分析性能

- 词表规则批量分析 600 条：**3323 ms**（无模型调用，0 token）
- 洞察接口：首次 1512 ms（含 1 次模型调用生成建议），缓存命中后 ~20 ms

### 洞察输出

```
total=600 avgScore=3.15 negative=277 rate=46.17% analyzed=600
topThemes:
   配送    110 条   39.71%
   分量     64 条   23.1%
   包装     48 条   17.33%
   口味     45 条   16.25%
   价格     25 条    9.03%
summary: 配送问题最突出（110条，占差评40%），分量不足和包装破损紧随其后。
         建议：1) 与配送站核对超时订单，对高频超时时段加派骑手或调整出餐节奏；
               2) 主食类明确标注克重并统一加量5%-10%，汤汁类改用封口膜+防漏盒，一周内复测差评率。
```

### 成本

- 600 条评价分析：**0 次模型调用**
- 洞察周报：**1 次调用 / 207 token**，按时间范围缓存 10 分钟
- 结论：把"最贵的工具"只用在"把数字讲成人话"这一步，其余全部规则化

---

## T14 微服务拆分（Nacos + OpenFeign）

目标：把 AI 网关拆成独立服务 `sky-ai-server`，用 Nacos 注册发现、OpenFeign 调用，并验证停服降级与恢复。
只拆一个 AI 服务，不声称"微服务架构"。

### 版本与环境

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 2.7.3 | 现有底座，`javax` |
| Spring Cloud | 2021.0.4 | 与 Boot 2.7.x 对齐 |
| Spring Cloud Alibaba | 2021.0.4.0 | 传递 Nacos Client 2.0.4 |
| Nacos Server | 2.3.2 standalone | 本机 `127.0.0.1:8848` |
| Resilience4j | 2.1.4 | 仅作为 Feign fallback 运行时，**未引入 Sentinel** |

Nacos 启动（Windows）：

```powershell
cd C:\IDEA\.tools\nacos\nacos\bin
.\startup.cmd -m standalone
# 就绪检查
curl.exe http://127.0.0.1:8848/nacos/v1/console/health/readiness
# -> OK
```

### 启动两个服务

```powershell
# AI 服务 8081（必须显式设置 Key；Start-Process 不继承用户级环境变量）
$env:DEEPSEEK_API_KEY=[Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')
Start-Process java -ArgumentList '-jar','sky-ai-server\target\sky-ai-server-1.0-SNAPSHOT.jar' `
  -WorkingDirectory 'C:\IDEA\sky-take-out' -WindowStyle Hidden

# 主服务 8080
Start-Process java -ArgumentList '-jar','sky-server\target\sky-server-1.0-SNAPSHOT.jar' `
  -WorkingDirectory 'C:\IDEA\sky-take-out' -WindowStyle Hidden
```

### V1 注册发现

```powershell
curl.exe "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=sky-ai-server"
curl.exe "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=sky-server"
```

结果：

```
sky-ai-server  hosts=1  127.0.0.1:8081  healthy=true  ephemeral=true
sky-server     hosts=1  127.0.0.1:8080  healthy=true  ephemeral=true
metadata.preserved.register.source=SPRING_CLOUD
```

结论：两个服务都以 `dev` 环境注册到 `DEFAULT_GROUP`，Nacos Client 2.0.4 与 Server 2.3.2 兼容。

### V2 主服务 → Feign → AI 同步推荐

```powershell
# 1）登录拿用户 token
curl.exe -sS -X POST -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@login.json" http://127.0.0.1:8080/user/user/login
# login.json: {"code":"test_code_001"}  -> userId=11, token 105 字节

# 2）调用主服务同步推荐（body 必须用 UTF-8 文件，避免 PowerShell 5.1 编码问题）
curl.exe -sS -X POST -H "authentication: <token>" `
  -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@q.json" `
  http://127.0.0.1:8080/user/ai/recommendSync
```

关键结果：

```
HTTP 200
code=1  mode=AI  degraded=false  items=5  elapsedMs=1366
parsed={"spicy":0,"flavors":["清淡"],"budgetPerPerson":80,"people":2}
items[0]={"dishId":47,"name":"北冰洋","price":4.00,"score":8}
ai_call_log: id=848 biz=RECOMMEND_PARSE model=deepseek-flash
             prompt_tokens=196 completion_tokens=39 cost_ms=1123
```

结论：主服务没有本地 AI 实现，`AiRemoteClient` 通过 Nacos 解析到 `sky-ai-server`，AI 服务完成模型调用、
检索与 `ai_call_log` 落库。

### V3 SSE 仍留在主服务

```powershell
curl.exe -sS -N --max-time 40 -X POST -H "authentication: <token>" `
  -H "Accept: text/event-stream" --data-binary "@q-sse.json" `
  http://127.0.0.1:8080/user/ai/recommend
```

结果：

```
event:parsed  data={"spicy":2,"people":3,"budgetPerPerson":33,...}
event:item    data={"dishId":47,...}      ×5
event:done    data={"mode":"AI","items":[...],"elapsedMs":1082}
```

结论：`SseEmitter` 和事件拆分逻辑留在主服务；主服务在固定线程池里调用 Feign 同步接口，再把结果转成事件。
Feign 是同步阻塞客户端，不支持流式返回，所以**没有**把 SSE 拆到 AI 服务。

### V4 用户身份透传

```powershell
# 带 X-User-Id 直连 AI 服务
curl.exe -X POST -H "X-User-Id: 99" -H "Content-Type: application/json" `
  --data-binary "@q.json" http://127.0.0.1:8081/ai/recommendSync
# -> code=1, mode=AI, items=5

# 不带 header
curl.exe -X POST -H "Content-Type: application/json" `
  --data-binary "@q.json" http://127.0.0.1:8081/ai/recommendSync
# -> {"code":0,"msg":"缺少用户身份"}
```

Redis 限流键：

```
ai:rate:99:2026102022 = 1
```

结论：主服务把 `BaseContext` 里的 `userId` 写入 `X-User-Id`，AI 服务拦截器解析到
`AiUserContext`，限流按用户计数；缺失身份时明确拒绝，不会出现 `userId=null` 的限流 NPE。

### V5 停掉 AI 服务 → 主服务降级而非 500

```powershell
$c = Get-NetTCPConnection -State Listen -LocalPort 8081
Stop-Process -Id $c.OwningProcess -Force

# 立即调用主服务（新 query，避免命中推荐缓存）
curl.exe -sS -o degrade1.json -w "%{http_code}" -X POST `
  -H "authentication: <token>" -H "Content-Type: application/json" `
  --data-binary "@q-degrade.json" `
  http://127.0.0.1:8080/user/ai/recommendSync
```

结果：

```
HTTP 200
code=1  mode=DEGRADED  degraded=true  needConfirm=true  items=5  elapsedMs=166
speech=AI 服务暂时不可用，先给你几个本店热销的～
items[0]={"dishId":47,"name":"北冰洋","reason":"本店热销"}
items[1]={"dishId":59,"name":"东坡肘子",...}
redis: EXISTS ai:circuit:open -> 1, TTL -> 15

# 第二次调用：主服务直接看熔断 key，不再等 Feign
HTTP 200  mode=DEGRADED  elapsedMs=15  （墙钟 48ms）
```

结论：Feign fallback 在主服务本地查热销菜，接口仍返回 200；同时写 Redis 熔断开关，15s 内快速失败。
停服后 Nacos 实例列表由 1 变 0。

### V6 恢复 AI 服务 → 自动恢复

```powershell
$env:DEEPSEEK_API_KEY=[Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')
Start-Process java -ArgumentList '-jar','sky-ai-server\target\sky-ai-server-1.0-SNAPSHOT.jar' `
  -WorkingDirectory 'C:\IDEA\sky-take-out' -WindowStyle Hidden

# 等 /ai/ping 与 Nacos 实例恢复，等 ai:circuit:open TTL 到期（约 15s）
curl.exe "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=sky-ai-server"
# -> hosts=1 healthy=true

# 再调主服务（新 query）
```

结果：

```
HTTP 200  code=1  mode=AI  degraded=false  items=5  elapsedMs=2050
ai_call_log: id=850 prompt_tokens=195 completion_tokens=39 cost_ms=1249
```

结论：无需重启主服务，熔断 key TTL 到期后自动放行探测，AI 服务恢复后业务回到 `mode=AI`。

### V7 拆分后的回归

| 项 | 命令 | 结果 |
|---|---|---|
| 管理端打标 | `POST /admin/ai/tag/refresh`（token 头） | `code=1, data=80`，2.95s；`ai_call_log` id=851 `DISH_TAG` 618/643 token |
| 商家洞察周报 | `POST /admin/comment/insight/refresh` → `GET /admin/comment/insight?begin=2026-10-14&end=2026-10-20` | 159 条评价 / 84 差评，周报 115 字，1.31s；`ai_call_log` id=852 `COMMENT_SUMMARY` 150/86 token |
| AI 状态 | `GET /user/ai/status` | 15ms 返回 `model/today/rateLimitPerHour`，数据来自 AI 服务 |
| 单测 | `mvn -B test` | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS |

> 周报建议原本在 `CommentServiceImpl` 里直接调 `LlmClient`。拆分后主服务只传统计结果，
> AI 服务统一负责模型调用与 `ai_call_log` 落库，主服务不再依赖 `LlmClient`/`AiCallLogMapper`。

### 踩坑记录

1. **Resilience4j TimeLimiter 默认 1s**：第一次 Feign 调用真实模型耗时 1.06s，被 TimeLimiter 判超时，
   直接走 fallback。日志：`TimeLimiter 'AiRemoteClient#recommendSync(...)' recorded a timeout exception`。
   修复：`resilience4j.timelimiter.configs.default.timeout-duration: 10s`，必须大于 Feign 读超时 8s。
2. **Feign fallback 需要 circuit breaker 运行时**：只加 `fallbackFactory` 不生效；设置
   `feign.circuitbreaker.enabled=true` 并引入 `spring-cloud-starter-circuitbreaker-resilience4j`。
   没有引入 Sentinel。
3. **Nacos 版本**：Spring Cloud Alibaba 2021.0.4.0 传递 Nacos Client 2.0.4，配 Nacos Server 2.3.2
   实测正常；不要混用 Spring Cloud 2022+（Boot 3 / Jakarta）。
4. **PowerShell 5.1 中文**：`Invoke-RestMethod` 响应按 ANSI 解码，中文变乱码；验证时统一用
   `curl.exe --data-binary @file` 发 UTF-8，落盘后用 UTF-8 读取。
5. **AI 服务直连安全**：`/ai/recommendSync` 强制要求 `X-User-Id`，避免绕过主服务 JWT 直接刷模型额度；
   `/ai/tag/refresh`、`/ai/status` 只允许内网访问（未暴露到公网）。

---

## T15 评价异步分析（RabbitMQ，6.2 方案 A）

目标：评价提交后只落库 + 发消息，消费者批量做规则分析；失败重试 3 次，重试耗尽进死信队列。
选 A 不选 B：不动订单超时主链路，改动集中在评价旁路，风险低。

### 环境

| 组件 | 版本/地址 |
|---|---|
| RabbitMQ | 3.12.1（WSL Ubuntu 24.04，`127.0.0.1:5672`） |
| 管理台 | `http://127.0.0.1:15672`，验收用户 `sky / sky123` |
| Spring AMQP | 2.4.6（Boot 2.7.3 管理） |
| amqp-client | 5.14.2 |

启动（本机验收用 WSL；也可换成 Docker/本机安装）：

```powershell
wsl -d Ubuntu-24.04 -u root service rabbitmq-server start
# 首次需要管理台时：
wsl -d Ubuntu-24.04 -u root rabbitmq-plugins enable rabbitmq_management
```

应用配置（`sky-server/src/main/resources/application.yml`）：

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:sky}
    password: ${RABBITMQ_PASSWORD:sky123}
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
```

### 拓扑

```
comment.exchange --comment.analyze--> comment.analyze.queue
                                          | x-dead-letter-exchange = comment.dlx
                                          v
                                     comment.dlx --comment.analyze.dlq--> comment.analyze.dlq
```

- 队列 `comment.analyze.queue`：durable，带 `x-dead-letter-exchange=comment.dlx`、
  `x-dead-letter-routing-key=comment.analyze.dlq`
- 批量参数：`batch-size=20`、`receive-timeout=1s`；`@RabbitListener` 参数为 `List<CommentAnalyzeMessage>`
- 重试：消费者内 `RetryTemplate`，3 次，退避 1s → 2s
- 幂等：`comment_analysis.uk_comment` 唯一键 + `upsert`

### V1 正常批量消费

连续提交 5 条评价（订单 1286–1290）：

```powershell
# 每条：POST /user/comment，header authentication，body 为 UTF-8 文件
# {"orderId":1286,"score":1,"content":"配送太慢，汤洒了，分量也少"}
```

应用日志：

```
23:44:26.8xx  INFO  CommentMessageProducer : 评价消息已投递, commentId=1315..1319
23:44:28.008  INFO  CommentServiceImpl  : MQ 批量分析落库完成, 请求 5 条, 实际 5 条
23:44:28.010  INFO  CommentAnalyzeConsumer : MQ 批量分析完成, 消息=5 条, 实际分析=5 条,
                  commentIds=[1315, 1316, 1317, 1318, 1319]
```

队列/分析结果：

```
comment.analyze.queue: messages=0 consumers=1
comment_analysis: 5 行，sentiment/themes 已写入
```

结论：5 条消息在 1 个批次内消费，批量落库；用户提交接口不等待分析。

### V2 重复投递幂等

向 `comment.exchange` 重复投递同一个 `commentId=1310` 两次：

```powershell
# 管理台 API 发布（payload 的 __TypeId__ 必须是消息类）
POST /api/exchanges/%2F/comment.exchange/publish
{"properties":{"content_type":"application/json","delivery_mode":2,
  "headers":{"__TypeId__":"com.sky.mq.CommentAnalyzeMessage"}},
 "routing_key":"comment.analyze",
 "payload":"{\"commentId\":1310,\"orderId\":1291,\"score\":5}",
 "payload_encoding":"string"}
```

结果：

```
23:42:39.755  INFO  CommentAnalyzeConsumer : MQ 批量分析完成, 消息=2 条, 实际分析=1 条,
                  commentIds=[1310, 1310]
comment_analysis where comment_id=1310 -> 1 行
```

结论：重复消息只会覆盖同一行，不会产生重复分析记录；`listByIds` 对重复 id 也只回一行。

### V3 失败重试 + 死信队列

投递一条指向不存在评价的毒消息（`commentId=999999`）：

```powershell
# 同上 publish API；payload: {"commentId":999999,"orderId":0,"score":1}
```

消费日志（3 次尝试，间隔 1s/2s）：

```
23:42:16.585  WARN  CommentServiceImpl : MQ 批量分析未找到评价, commentIds=[999999]
23:42:17.599  WARN  CommentServiceImpl : MQ 批量分析未找到评价, commentIds=[999999]
23:42:19.608  WARN  CommentServiceImpl : MQ 批量分析未找到评价, commentIds=[999999]
23:42:19.613  ERROR CommentAnalyzeConsumer : 评价批量分析重试耗尽, 转死信队列, 消息=1 条,
                  commentIds=[999999]
```

队列状态（`rabbitmqctl list_queues`）：

```
comment.analyze.queue    messages=0
comment.analyze.dlq      messages=1
```

死信消息内容（管理台 get）：

```json
{"commentId":999999,"orderId":0,"score":1,"submittedAt":null}
```

结论：重试 3 次后逐条转发到 `comment.dlx`，原消息 ack；毒消息不会无限重投，也不会阻塞后续批次
（同一批里的正常消息会随整批一起进入死信，这是批量重试的取舍）。

### V4 发布失败兜底（MQ 停机）

停掉 RabbitMQ 应用（`rabbitmqctl stop_app`）后提交评价（订单 1301）：

```powershell
POST /user/comment
{"orderId":1301,"score":2,"content":"MQ 停机时的评价，先落库"}
```

结果：

```
HTTP 200, wallMs=51
DB: comment id=1320, order_id=1301, analyzed=0
LOG 23:45:27.200 ERROR CommentMessageProducer : 评价消息投递失败, commentId=1320,
      评价已保存，可用 /admin/comment/analyze 补偿
```

结论：MQ 故障不把用户提交打成 500，评价先落库；消息没有进队列，需要补偿。

恢复 RabbitMQ 后调管理端补偿：

```powershell
POST /admin/comment/analyze?limit=200   # token 头
# -> {"code":1,"data":1}
```

```
DB: comment id=1320, analyzed=1
```

### V5 恢复后消费自动恢复

`rabbitmqctl start_app` 后，再提交一条评价（订单 1302）：

```
23:45:43.777 INFO CommentAnalyzeConsumer : MQ 批量分析完成, 消息=1 条, 实际分析=1 条,
                 commentIds=[1321]
comment.analyze.queue: messages=0 consumers=1
```

结论：Spring AMQP 监听容器自动重连，恢复后新评价继续走 MQ，无需重启主服务。

### V6 测试

```powershell
mvn -B test
# Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

测试类通过 `sky.mq.enabled=false` 关闭队列声明与消费者，不依赖 RabbitMQ。

### 踩坑

1. **PowerShell 5.1 写 JSON 文件默认带 BOM**：管理台 API 会返回 `{"error":"bad_request","reason":"not_json"}`。
   用 `New-Object System.Text.UTF8Encoding($false)` 写无 BOM 文件。
2. **Spring AMQP 批量监听要显式开 `batchListener`**：只配 Boot 的 `consumer-batch-enabled` 不够；
   自定义 `SimpleRabbitListenerContainerFactory` 并 `setBatchSize`、`setReceiveTimeout`。
3. **重试 advice 在批量监听器上与 `RejectAndDontRequeueRecoverer` 组合行为不稳定**：改为消费者内
   `RetryTemplate` + 显式转发死信，行为可观测、可复跑。
4. **Docker Hub 拉取超时**：本机 Docker daemon 可用但 `rabbitmq:3.13-management` 拉不下来；
   验收改用 WSL Ubuntu 的 `rabbitmq-server 3.12.1`。Docker Compose 已补 RabbitMQ 服务，
   但未实际 `compose up`。