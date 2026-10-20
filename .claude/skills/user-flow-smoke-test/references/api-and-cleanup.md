# API and Cleanup Reference

## Application

- Main class: `sky-server/src/main/java/com/sky/SkyApplication.java`
- Default base URL: `http://127.0.0.1:8080`
- Active profile: `dev`
- MySQL database: `sky_take_out`
- Redis database: `1`
- API success convention: JSON `code == 1`

Connection values must come from `application.yml`, `application-dev.yml`, environment variables, or explicit runtime parameters. Do not copy plaintext credentials into the skill or its reports.

## Authentication

`sky-server/src/main/java/com/sky/config/WebMvcConfiguration.java` registers only `JwtTokenAdminInterceptor`:

- Protected: `/admin/**`
- Excluded: `/admin/employee/login`
- Token header: `token`

The current project does not register a user JWT interceptor. `/user/**` requests do not populate `BaseContext`, so an administrator Token cannot represent a C-end user.

Service-level user-flow tests must set and remove the ThreadLocal in the same thread:

```java
BaseContext.setCurrentId(tempUserId);
try {
    // invoke services
} finally {
    BaseContext.removeCurrentId();
}
```

## Read-only HTTP Coverage

### Administrator

```text
POST /admin/employee/login
GET  /admin/dish/page?page=1&pageSize=1
```

Login body:

```json
{
  "username": "from SKY_SMOKE_ADMIN_USERNAME",
  "password": "from SKY_SMOKE_ADMIN_PASSWORD"
}
```

Keep the returned JWT in memory only.

### User catalog

```text
GET /user/category/list?type=1
GET /user/category/list?type=2
GET /user/dish/list?categoryId=<active dish category ID>
GET /user/setmeal/list?categoryId=<active setmeal category ID>
GET /user/setmeal/dish/<active setmeal ID>
```

Choose IDs from preceding successful responses. Treat an empty list as a data precondition issue, not as a transport failure. Do not invent IDs.

### User write endpoints

```text
POST   /user/addressBook
GET    /user/addressBook/list
POST   /user/shoppingcart/add
GET    /user/shoppingcart/list
DELETE /user/shoppingcart/clean
POST   /user/order/submit
```

Do not call these over HTTP until a real user authentication/interceptor flow exists. The fixed Spring integration template covers address, cart, and order service behavior safely.

## Redis Cache

Current cache annotations use:

```text
dishCache::<categoryId>
setmealCache::<categoryId>
```

`CacheConfiguration` configures a 30-minute TTL. Test by calling the same read endpoint twice, scanning only for the exact expected key, and checking that TTL is positive and no greater than the configured TTL plus a small clock tolerance.

Do not delete existing cache keys. Never use `FLUSHDB`, `FLUSHALL`, or broad key deletion.

## Temporary Data Marker

Generate a unique marker shorter than all target varchar limits:

```text
sky-smoke-<epoch>-<random6>
```

Store it in:

| Table | Field |
|---|---|
| `user` | `openid` |
| `address_book` | `detail` |
| `shopping_cart` | `dish_flavor` |
| `orders` | `remark` |

Use a database-generated user ID. Do not use an existing user ID or a fixed high numeric ID.

Select one existing enabled dish with a read-only query. Create the address and shopping cart through `AddressBookService` and `ShoppingCartService` so their business paths are covered. Invoke `OrderService.submitOrder` directly after setting `BaseContext`.

## Business Assertions

Before cleanup, assert:

1. A unique temporary user exists.
2. A unique marker address belongs to that user.
3. A marker shopping cart row belongs to that user and references an enabled dish.
4. A successful order result has a non-null ID and order number.
5. `orders.user_id` and `orders.remark` match the temporary identity and marker.
6. The number of `order_detail` rows equals the number of cart rows used for the order.
7. The temporary user's cart is empty after a successful order.

## Cleanup Algorithm

Always run cleanup in `finally`, even when mapper binding or assertion failures occur.

1. Query order IDs with both `user_id = tempUserId` and `remark = marker`.
2. Delete `order_detail` rows by those captured order IDs.
3. Delete each `orders` row by `id`, `user_id`, and `remark`.
4. Query/delete each `shopping_cart` row by captured `id`, `user_id`, and `dish_flavor`.
5. Query/delete each `address_book` row by captured `id`, `user_id`, and `detail`.
6. Delete `user` by generated `id` and `openid`.
7. Verify all marker-based residue counts are zero.
8. Call `BaseContext.removeCurrentId()` regardless of cleanup outcome.

If a query returns unexpected records, retain strict marker and ID constraints. Report the anomaly; do not broaden deletion conditions.

Forbidden cleanup operations:

```text
TRUNCATE
FLUSHDB
FLUSHALL
DELETE FROM <table> without marker/ID constraints
Deleting an entire cache prefix
Deleting guessed IDs
```

## Known Failure Signature

Current source may contain:

```xml
<foreach collection="orderDetailList" item="od" separator=",">
    (..., #{ad.amount})
</foreach>
```

Location: `sky-server/src/main/resources/mapper/OrderDetailMapper.xml:9`

Expected item reference: `od.amount`.

If runtime output contains `Parameter 'ad' not found`, classify it as:

```text
CART_ADDRESS_ORDER | KNOWN_SOURCE_DEFECT
```

Impact: the order row may already exist because `OrderServiceImpl.submitOrder` inserts it before inserting details and currently has no transaction annotation. Recover order IDs from `orders` using both the temporary user ID and marker, then perform normal precise cleanup. Do not modify the mapper automatically.

## Status Definitions

- `PASS`: The phase completed and all assertions passed.
- `FAIL`: The phase executed but behavior was incorrect or an unexpected exception occurred.
- `SKIP`: The phase was intentionally not applicable after a documented earlier result.
- `BLOCKED`: Required service, credentials, active catalog data, or expected structure was unavailable; no unsafe fallback was attempted.
- `KNOWN_SOURCE_DEFECT`: Runtime failure matches a documented source defect and cleanup completed or was attempted.
- `CLEANUP_FAILED`: Some temporary data could not be safely removed with strict constraints. Report exact residue counts and stop; never widen deletion conditions.
