# 上游接口接入层（API Integration Gateway）

在 Spring Boot 工程内搭建的对外“上游接口接入层”，并用一个**跑在本地的模拟上游**充当第三方服务。
目标是让多版本差异、重复/并发重复请求、以及上游异常下的对外行为都**稳定、可预期、可区分**。

- 业务示例：订单查询 `POST /api/gateway/orders/{orderId}/query`
- 模拟上游：同应用内的 `/mock-upstream/v1|v2|v3/orders/{orderId}`，用请求头注入故障
- 技术栈：Spring Boot 3.4 / Java 21 / `RestClient` / 内存组件（无需外部中间件）

---

## 1. 对外接口

### 请求

```
POST /api/gateway/orders/{orderId}/query
X-Client-Id:     <客户端标识，限流维度；缺省 anonymous>
X-Idempotency-Key:<幂等键，可选；提供后启用幂等单飞与重放>
X-Api-Version:   v1 | v2 | v3（可选，缺省 v3；无法识别返回 400）
X-Mock-Mode:     <仅本地模拟用，见第 5 节>
```

### 成功响应（HTTP 200）

```json
{
  "success": true,
  "replay": false,
  "degraded": false,
  "requestId": "req-…",
  "apiVersion": "V2",
  "order": {
    "orderId": "o-1", "userId": "user-1",
    "amount": 12.34, "currency": "CNY",
    "status": "UNPAID", "createdAtEpochMilli": 1767323045000
  }
}
```

- `replay=true`：本次为幂等重放（顺序重复或并发等待），结果与首次完全一致（含同一 `requestId`）。
- `degraded=true`：上游不可用且熔断打开，返回该订单最近一次成功结果（降级）。

### 确定性业务拒绝（HTTP 200，`success=false`）

缺字段、字段类型不匹配、上游 404 属于**确定性**结果，会被幂等固化、可重放：

```json
{ "success": false, "replay": false, "requestId": "req-…",
  "apiVersion": "V2", "rejectReason": "MISSING_FIELD",
  "message": "[v=V2] 缺少必填字段: currency" }
```

### 系统级错误（非 2xx，统一错误体，不透出来源异常）

| HTTP | errorCode / rejectReason | 含义 |
|------|--------------------------|------|
| 400  | `VERSION_UNSUPPORTED`    | 版本不在兼容范围；且**不会**调用上游 |
| 404  | `UPSTREAM_ORDER_NOT_FOUND` | 上游 404（确定性，不重试），也以 `success=false` 体返回 |
| 429  | `RATE_LIMITED`           | 客户端超过固定窗口配额 |
| 502  | `MISSING_FIELD` / `FIELD_TYPE_MISMATCH` | 上游报文归一失败 |
| 503  | `UPSTREAM_UNAVAILABLE`   | 超时/5xx/内部异常重试耗尽，或熔断打开且无降级数据 |
| 500  | `INTERNAL_ERROR`         | 未预期异常的兜底，不含内部堆栈 |

上游的 `SocketTimeoutException`、`IllegalStateException` 等原始异常**不会**透出给调用方。

---

## 2. 版本兼容范围与归一规则

当前兼容 **V1 / V2 / V3**，缺省版本 **V3**。三版字段结构不同，统一归一为同一对外模型：

| 对外字段 | V1 | V2 | V3 |
|----------|----|----|----|
| orderId | `orderId` | `order_no` | `id` |
| userId | `userId` | `user_id` | `customer` |
| amount（元，BigDecimal） | `money` 字符串“元” | `amount` 整数“分”（÷100） | `amount.value`（字符串/数字“元”） |
| currency | 固定 `CNY` | `currency` | `amount.currency` |
| status | `status`：0/1/2 → UNPAID/PAID/REFUNDED | `status`：pending/unpaid/paid/refunded | `state`：CREATED/UNPAID/PAID/REFUNDED |
| createdAtEpochMilli | `createdTime` 秒（×1000） | `created_at` 毫秒 | `createdAt` ISO-8601 |

不静默跳过、且原因可区分：

- 缺必填字段（含空字符串/JSON null）→ `MISSING_FIELD`
- 字段存在但类型/取值无法转换（金额不可解析、状态码未知、时间不是 ISO-8601 等）→ `FIELD_TYPE_MISMATCH`
- 版本头无法识别 → `VERSION_UNSUPPORTED`（在调用上游之前拒绝）

判定依据写入日志：显式头 `basis=header:v2`；缺省时 `basis=header-absent->default:V3`。

---

## 3. 幂等规则

- 维度：`(X-Client-Id, X-Idempotency-Key)`。
- 同一键：**真正打到上游只有一次**。内部使用 single-flight（`ConcurrentHashMap` + `CompletableFuture`），
  并发重复提交时其余线程等待并复用同一个结果，返回与首次完全一致的响应（订单内容与 `requestId` 都一致）。
- 固化范围：**成功**与**确定性业务拒绝**（缺字段/类型不匹配/404 等）都会被保留用于重放。
- 不固化范围：上游**临时失败**（超时/5xx/连接异常）属于非确定性结果，不写入幂等存储，
  稍后用同一键重试仍可成功。
- 不提供幂等键时，按普通请求执行，结果不保留、不重放。
- 限流计数先于幂等判定：即使是重放也占用客户端配额（见下）。

---

## 4. 限流、重试、熔断与降级

### 限流（按客户端，固定窗口）
- 窗口内超过 `gateway.rate-limit.permits-per-window` 的请求直接 `429 RATE_LIMITED`。
- 不同 `X-Client-Id` 计数互相隔离；窗口到期自动滚动重置。

### 重试（仅临时失败）
- 适用：上游超时、5xx、连接/内部异常。404、缺字段、类型不匹配**不重试**。
- 策略：最多尝试 N 次（含首次），指数退避 `backoff * multiplier^(attempt-1)`。

### 熔断
- 连续失败达到阈值后**打开**：期间请求不再打上游，直接走降级或快速失败，避免拖垮后续请求。
- 打开时长过后进入**半开**，仅放一个试探请求：成功→关闭并清零；失败→重新计时打开。

### 降级
- 每次成功按 `orderId` 缓存最近一次归一结果。
- 熔断打开时若有缓存：返回缓存订单且 `degraded=true`（仍为成功，幂等可重放）。
- 无缓存：`503 UPSTREAM_UNAVAILABLE`。
- 注意：熔断**未**打开、仅重试耗尽时不消费缓存（按失败抛出以累计熔断），避免用旧数据掩盖持续故障。

---

## 5. 本地模拟上游

| 端点 | 说明 |
|------|------|
| `GET /mock-upstream/v1/orders/{id}` 等 | 返回对应版本异构报文 |
| `GET /mock-upstream/admin/stats`     | 查看各版本及总“真正被调用次数”（测试据此断言只打一次） |
| `POST /mock-upstream/admin/reset`    | 清零计数 |

用 `X-Mock-Mode` 注入故障：`NORMAL` / `TIMEOUT` / `ERROR_503` / `ERROR_500` / `INTERNAL_ERROR`
/ `NOT_FOUND` / `MISSING_FIELD` / `TYPE_MISMATCH`。

---

## 6. 配置项（`application.properties`）

| 键 | 默认 | 说明 |
|----|------|------|
| `server.port` | `0` | 对外端口，0=随机 |
| `gateway.upstream.base-url` | 空 | 模拟上游基址；测试运行时注入随机端口 |
| `gateway.upstream.read-timeout` | `300ms` | 读超时，超时归类为 `UPSTREAM_TIMEOUT` |
| `gateway.retry.max-attempts` | `3` | 临时失败最大尝试次数（含首次） |
| `gateway.retry.backoff` | `20ms` | 退避初始间隔 |
| `gateway.retry.multiplier` | `2.0` | 退避倍数 |
| `gateway.circuit-breaker.failure-threshold` | `5` | 触发熔断的连续失败次数 |
| `gateway.circuit-breaker.open-duration` | `10s` | 熔断打开时长，之后半开试探 |
| `gateway.rate-limit.permits-per-window` | `10` | 每客户端每窗口最大请求数 |
| `gateway.rate-limit.window` | `1m` | 固定窗口长度 |

---

## 7. 测试覆盖

- `VersionNormalizationIntegrationTest`：v1/v2/v3 归一一致、缺省版本、缺字段、类型不匹配、版本不支持（不打上游）。
- `IdempotencyIntegrationTest`：顺序重放、12 线程并发重复提交（仅 1 次首次、1 次上游调用、结果一致）、异键隔离。
- `RateLimitIntegrationTest`：超配额 429、客户端间隔离。
- `UpstreamFailureIntegrationTest`：超时/503/内部异常重试次数与归一错误、404 不重试、不透出原始异常、临时失败不固化。
- `CircuitBreakerIntegrationTest`：连续失败打开熔断、打开期返回降级缓存且不打上游、无缓存 503。
- `LoggingObservabilityTest`：决策日志含 `requestId`、命中 `version` 与判定依据 `basis`（含重放 `idempotency-hit`）。

运行：

```bash
mvn test
```
