---
name: user-flow-smoke-test
description: This skill should be used when the user asks to "测试今天新增功能", "跑用户端冒烟测试", "测试购物车和下单", "验证下单流程", "快速测试苍穹外卖", or invokes "/user-flow-smoke-test" for the Sky Take Out project.
version: 1.0.0
---

# Sky Take Out User Flow Smoke Test

## Purpose

对 `sky-take-out` 当前工作树执行安全、可重复的用户端冒烟测试。覆盖管理员真实登录、分类/菜品/套餐只读接口、Redis 缓存，以及通过独立 Spring 测试上下文执行的地址、购物车和下单流程。

不得通过管理员 Token 冒充 C 端用户。当前用户端没有 JWT 拦截器，地址、购物车和订单服务通过 `BaseContext` 的 ThreadLocal 获取用户 ID，因此订单阶段必须使用固定测试模板在同一测试线程中设置数据库生成的临时用户 ID。

## Required Workflow

按以下顺序执行，不跳过清理和残留验证：

1. `PRECHECK`
2. `ADMIN_LOGIN`
3. `USER_CATALOG`
4. `REDIS_CACHE`
5. `CART_ADDRESS_ORDER`
6. `CLEANUP`
7. `RESIDUE_CHECK`
8. `SUMMARY`

每个阶段仅报告 `PASS`、`FAIL`、`SKIP`、`BLOCKED`、`KNOWN_SOURCE_DEFECT` 或 `CLEANUP_FAILED`，附简短且脱敏的原因。

## Preconditions

从项目根目录运行。先确认存在：

- `pom.xml`
- `sky-server/pom.xml`
- `sky-server/src/main/java/com/sky/SkyApplication.java`
- 本 skill 的 `scripts/run-user-flow-smoke-test.ps1`

记录运行前的 `git status --short`。当前工作树可能已有未提交改动；不得执行 `git reset`、`git clean`、checkout 覆盖、自动格式化或任何自动修复业务源码的操作。

管理员凭据仅从当前会话环境变量读取：

- `SKY_SMOKE_ADMIN_USERNAME`
- `SKY_SMOKE_ADMIN_PASSWORD`

可选覆盖项：

- `SKY_SMOKE_BASE_URL`，默认 `http://127.0.0.1:8080`
- `SKY_SMOKE_MYSQL_HOST` / `SKY_SMOKE_MYSQL_PORT`
- `SKY_SMOKE_REDIS_HOST` / `SKY_SMOKE_REDIS_PORT` / `SKY_SMOKE_REDIS_DB`
- `SKY_SMOKE_REDIS_PASSWORD`
- `SKY_SMOKE_MVN`
- `SKY_SMOKE_REDIS_CLI`

不得在聊天、终端摘要或文件中打印密码、完整 JWT、Redis 密码或完整敏感连接串。缺少管理员凭据时，将 `ADMIN_LOGIN` 标记为 `BLOCKED`，继续可安全执行的用户端只读和订单集成测试；不得猜测账号或密码。

## Execute the Fixed Test Driver

运行固定脚本，而不是每次重新生成任意 SQL 或测试代码：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .Codex\skills\user-flow-smoke-test\scripts\run-user-flow-smoke-test.ps1
```

脚本负责：

- 使用命名 mutex 防止并发测试互相清理。
- 检查 MySQL、Redis 和 HTTP 服务可达性。
- 真实登录管理员并用内存中的 Token 调用一个受保护的只读接口。
- 查询用户端分类、菜品、套餐和套餐明细。
- 对同一分类重复读取并只读验证具体 Redis 缓存键及 TTL。
- 从固定模板创建唯一的临时 JUnit 文件，仅运行指定测试。
- 在独立 Spring 上下文中创建临时用户、地址和购物车，设置 `BaseContext`，调用真实下单服务并断言结果。
- 无论测试成功还是失败，都精确清理临时订单明细、订单、购物车、地址和用户。
- 删除临时测试源码、编译产物和本次 Surefire 报告。
- 对比运行前后的 Git 工作树状态。

不得运行全量 `mvn test`。项目现有旧测试会访问固定 8080 或写入非 marker Redis 数据，不属于本流程。

## Inspect Today’s Changes

运行固定脚本前后，查看：

```bash
git status --short
git diff --name-only
git diff -- '*Controller.java'
```

识别当天新增或修改的 Controller 接口。固定脚本已覆盖核心用户链路；对于新出现且未覆盖的 `GET` 只读接口，可在脚本完成后追加只读请求，并从现有数据库/API 响应中选择参数。

不得为未知 ID 猜值，不得为了“补齐覆盖”调用新增的 `POST`、`PUT`、`DELETE` 接口。写操作仅允许由固定订单模板执行。

## Authentication Rules

管理员接口必须使用 `/admin/employee/login` 返回的真实 Token。验证：

- 登录响应 HTTP 成功。
- `Result.code == 1`。
- `data.token` 非空。
- 携带 Token 的只读 `/admin/**` 请求成功。

Token 仅保留在脚本内存中，不写入文件或最终报告。

不要把管理员 Token 添加到 `/user/**` 请求来模拟用户身份。用户端服务测试通过：

```java
BaseContext.setCurrentId(tempUserId);
try {
    // address, cart, order services
} finally {
    BaseContext.removeCurrentId();
}
```

这里的 `tempUserId` 必须来自本次插入的、带唯一 marker 的测试用户记录，不得硬编码真实用户 ID。

## Data Safety Rules

临时 marker 使用 `sky-smoke-<timestamp>-<random>`，并写入可验证字段：

- `user.openid`
- `address_book.detail`
- `shopping_cart.dish_flavor`
- `orders.remark`

测试商品必须从现有 `status = 1` 的菜品中只读选择。没有可用商品时报告 `BLOCKED`，不得创建伪造菜品。

清理顺序固定为：

1. `order_detail`
2. `orders`
3. `shopping_cart`
4. `address_book`
5. `user`

每次删除必须使用本次捕获的具体 ID、临时用户 ID 和 marker 中可用的全部约束。清理失败时报告原始错误，不得放宽 WHERE 条件重试。

严格禁止：

- `TRUNCATE`
- `FLUSHDB` / `FLUSHALL`
- 无 marker 的全用户删除
- 删除整个缓存前缀
- `KEYS *` 后批量删除
- 删除未知或猜测的 ID
- 自动修改导致测试失败的业务源码

## Failure Classification

遇到以下 MyBatis 错误：

```text
Parameter 'ad' not found
```

将其分类为：

```text
CART_ADDRESS_ORDER | KNOWN_SOURCE_DEFECT
```

并指向 `sky-server/src/main/resources/mapper/OrderDetailMapper.xml:9`。当前 `foreach` item 为 `od`，金额却引用 `ad.amount`。此错误可能发生在订单主表已插入、订单明细尚未完成时；必须继续执行 marker 限定的清理。

不得由 skill 自动修复该 XML。若源码已由开发者修复，则让完整订单流程正常通过，不再报告已知缺陷。

## Reporting

最终摘要必须包含：

- 每个阶段的状态。
- 实际覆盖的接口，不展示 Token。
- 分类、菜品、套餐和缓存验证结果。
- 下单是否成功创建订单与明细、是否清空购物车。
- 若失败，给出具体异常、业务阶段和可点击的源码位置。
- 临时用户、地址、购物车、订单和明细的残留计数。
- Git 工作树是否保持与运行前一致。
- 明确说明是否跳过了任何测试及原因。

完成报告前读取 `references/api-and-cleanup.md`，核对 API、缓存键和清理约束。

## Resources

- `scripts/run-user-flow-smoke-test.ps1`：固定测试驱动器。
- `scripts/UserFlowOrderSmokeTest.java.template`：安全的临时 Spring/JUnit 订单测试模板。
- `references/api-and-cleanup.md`：接口、上下文、缓存、故障分类和清理参考。
