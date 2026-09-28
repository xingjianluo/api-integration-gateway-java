# API Integration Gateway（上游接口接入层）

对外提供一个统一的支付接入接口 `POST /api/v1/payments`，后端调用一个**跑在本地的模拟上游**
（与本应用同进程，路径前缀 `/mock-upstream`）来充当第三方服务。网关解决四件事：

1. **多版本结果归一**：上游 v1/v2/v3 字段结构不同，统一成同一套对外模型；
   缺字段、类型不匹配、版本不支持都以**可区分的错误码**明确拒绝，不静默跳过。
2. **幂等**：同一幂等键在 TTL 内真正只打到上游一次；重复提交、并发重复提交都返回
   与首次完全一致的结果。
3. **限流**：按客户端固定窗口配额限流，超额明确拒绝。
4. **韧性**：上游超时 / 非成功响应 / 内部异常归一成可区分错误类型，不泄漏原始异常；
   临时失败指数退避重试；连续失败熔断降级，不拖垮后续请求。

---

## 1. 快速开始

```bash
mvn spring-boot:run          # server.port=0，启动端口见日志（测试用）
mvn test                     # 运行全部 11 个测试类（单元 + 集成）
```

生产/本地固定端口可在 `application.properties` 把 `server.port` 改为具体端口。
模拟上游地址默认 `http://localhost:${server.port}/mock-upstream`，启动后会自动把
字面端口 `0` 替换为实际监听端口。

### 对外请求示例

```bash
curl -X POST http://localhost:<port>/api/v1/payments \
  -H 'Content-Type: application/json' \
  -H 'X-Client-Id: client-001' \
  -H 'X-Api-Version: v2' \
  -H 'Idempotency-Key: order-123' \
  -d '{"orderRef":"order-123","amount":100,"currency":"USD"}'
```

成功响应（不同版本结构被归一为同一模型）：

```json
{
  "payment": {
    "transactionId": "MOCK-order-123",
    "amount": 100,
    "currency": "USD",
    "status": "SUCCESS",
    "sourceVersion": "v2"
  },
  "idempotentReplay": false
}
```

失败响应（统一结构，错误码可区分，`retryable` 标识是否临时失败）：

```json
{
  "requestId": "49828bcfcde8442d",
  "errorCode": "UPSTREAM_MISSING_FIELD",
  "message": "上游响应缺少必需字段: v2.money",
  "retryable": false
}
```

### 请求头说明

| Header | 必填 | 说明 |
|---|---|---|
| `X-Client-Id` | 否 | 客户端标识，用于按客户端限流；缺省 `anonymous` |
| `X-Api-Version` | 否 | 指定上游版本 `v1`/`v2`/`v3`；缺省走默认版本（默认 v1） |
| `Idempotency-Key` | 是* | 幂等键，也可放在请求体 `idempotencyKey` |
| `X-Request-Id` | 否 | 调用方追踪标识；缺省时网关注生成并在响应头回带 |
| `X-Test-Scenario` | 否 | **仅联调/测试用**，透传给模拟上游控制其行为 |

请求体：`{"idempotencyKey?","version?","orderRef","amount","currency"}`

---

## 2. 版本兼容范围

网关当前支持 `v1`、`v2`、`v3`，可通过 `gateway.version.supported` 配置收窄；
`gateway.version.default-version` 配置未显式指定版本时的兜底版本。

上游各版本字段结构（模拟上游按版本返回异构 JSON）：

| 归一字段 | v1 | v2 | v3 |
|---|---|---|---|
| 交易号 | `txn_id` (string) | `paymentId` (string) | `id` (string) |
| 金额 | `amount` (number) | `money.value` (number) | `amount.value` (number) |
| 币种 | `currency` (string) | `money.currencyCode` (string) | `amount.currency` (string) |
| 状态 | `state`: `OK/PROCESSING/FAIL` | `result`: `SUCCESS/PENDING/FAILED` | `paid`: `true/false/null` |

状态归一：`SUCCESS / PENDING / FAILED`（v3 `paid=null` 归一为 `PENDING`）。
归一结果额外带 `sourceVersion` 标明数据来源版本。

**拒绝原因严格区分（不静默跳过）：**

- 版本号无法识别或不在网关支持范围 → `VERSION_UNSUPPORTED`（日志 basis 记录判定依据）；
- 上游侧不支持该版本（模拟上游返回 404）→ 同样归一为 `VERSION_UNSUPPORTED`；
- 响应缺必需字段 → `UPSTREAM_MISSING_FIELD`（消息中给出字段路径，如 `v2.money`）；
- 字段类型错误或枚举值非法 → `UPSTREAM_TYPE_MISMATCH`；
- 响应体不是合法 JSON / Content-Type 非 JSON → `UPSTREAM_MALFORMED_RESPONSE`。

版本判定依据写入 MDC 与日志：客户端显式指定为 `basis=client-specified`，
走默认配置为 `basis=default-config`。

---

## 3. 幂等规则

- 幂等键由 `Idempotency-Key` 头或请求体 `idempotencyKey` 提供；键 + 请求体摘要
  （orderRef/amount/currency/version 的 SHA-256）共同确定一笔请求。
- TTL（默认 10 分钟）内：
  - **第一个请求（leader）真正调用上游一次**，结果（成功或失败）被缓存；
  - 顺序重复提交：直接返回缓存结果，`idempotentReplay=true`，**不再打上游**；
  - **并发重复提交**：后续线程（follower）在条件上等待 leader，完成后返回与首次
    **完全一致**的结果，上游调用次数仍为 1；
  - 同一幂等键但请求体摘要不同 → `IDEMPOTENCY_CONFLICT`（HTTP 409），立即拒绝；
  - follower 等待超过 `gateway.idempotency.acquire-timeout`（默认 5s）→
    返回明确错误，提示用相同幂等键稍后重试，**不会自行再打一次上游**；
  - 首次失败也会被缓存（含失败结果），TTL 内重放返回相同失败，不重复触发上游。
- 过期记录由后台定时任务清理（`gateway.idempotency.sweep-delay-ms`）。
- 当前实现为**进程内内存存储**（单实例语义）；多实例部署需替换为 Redis 等共享存储
  （实现 `IdempotencyStore` 接口即可）。

---

## 4. 限流规则

- 维度：按 `X-Client-Id`（缺省 `anonymous`）独立计数，客户端之间互不影响。
- 算法：固定窗口。每个客户端在任意长度为 `gateway.rate-limit.window` 的窗口内最多
  `gateway.rate-limit.permits-per-window` 次请求（默认 10 次/分钟，**含幂等重放**）。
- 窗口随该客户端首次请求滚动，窗口结束自动重置。
- 超额请求在进入幂等/上游调用前直接拒绝：HTTP 429 + `RATE_LIMITED`，不打上游。

---

## 5. 上游失败处理

### 错误码与 HTTP 状态

| errorCode | HTTP | retryable | 含义 |
|---|---|---|---|
| `UPSTREAM_TIMEOUT` | 502 | true | 连接/读超时（兼容 JDK HttpClient 与 HttpURLConnection 两种超时形态） |
| `UPSTREAM_SERVER_ERROR` | 502 | true | 上游 5xx（500 等） |
| `UPSTREAM_BAD_GATEWAY` | 502 | true | 上游 502/503/504 或无法建立有效通信 |
| `UPSTREAM_BAD_REQUEST` | 502 | false | 上游 400/422 等 |
| `VERSION_UNSUPPORTED` | 502 | false | 版本不支持（网关侧或上游 404） |
| `UPSTREAM_MISSING_FIELD` | 502 | false | 响应缺必需字段 |
| `UPSTREAM_TYPE_MISMATCH` | 502 | false | 字段类型/取值不匹配 |
| `UPSTREAM_MALFORMED_RESPONSE` | 502 | false | 响应不是合法 JSON |
| `UPSTREAM_CIRCUIT_OPEN` | 503 | false | 熔断打开，快速失败降级 |
| `RATE_LIMITED` | 429 | false | 超过客户端配额 |
| `IDEMPOTENCY_CONFLICT` | 409 | false | 幂等键被不同请求内容占用 |
| `INTERNAL_ERROR` | 500 | false | 网关内部错误（原始异常仅进服务端日志，不外泄） |

### 重试

仅对 `retryable=true` 的临时失败（超时、5xx、网关通信失败）做**指数退避重试**：
`backoff * multiplier^(attempt-1)`，上限 `max-backoff`；达到 `max-attempts` 仍失败则
抛出最后一次错误。非临时失败（4xx、字段问题、版本问题）不重试，避免无意义放大。

### 熔断降级

连续失败（仅统计超时/5xx 等上游基础设施失败）达到阈值后熔断器 OPEN：
之后所有请求**不再打上游**，直接快速返回 `UPSTREAM_CIRCUIT_OPEN`（503），
保证上游持续故障时不会被拖垮、也不继续增加上游负担；冷却期结束放一个探测请求
（HALF_OPEN），成功则关闭熔断恢复正常。

---

## 6. 可观测性 / 日志

每个请求由 `RequestIdFilter` 生成或沿用 `X-Request-Id`，写入 SLF4J MDC；
日志 pattern 统一带：

```
[rid=<requestId>,v=<命中版本>,basis=<版本判定依据>]
```

关键决策点（版本判定、上游调用结果、重试、熔断、幂等命中/冲突、限流、最终对外结果）
均打印 `requestId`、命中的接口版本与判定依据；测试中用 logback `ListAppender` 断言这些字段。

---

## 7. 配置项一览（`application.properties`，前缀 `gateway`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `gateway.upstream.base-url` | `http://localhost:${server.port}/mock-upstream` | 上游基址 |
| `gateway.upstream.connect-timeout` | `2s` | 连接超时 |
| `gateway.upstream.read-timeout` | `1s` | 读超时（测试中调小到 300ms 验证超时） |
| `gateway.upstream.payments-path` | `/payments` | 支付接口路径 |
| `gateway.version.default-version` | `v1` | 未指定版本时的默认版本 |
| `gateway.version.supported` | `v1,v2,v3` | 网关支持的版本范围 |
| `gateway.idempotency.ttl` | `10m` | 幂等结果存活时间（成功与失败都缓存） |
| `gateway.idempotency.acquire-timeout` | `5s` | 并发重复请求等待首请求的最长时间 |
| `gateway.idempotency.sweep-delay-ms` | `60000` | 过期幂等记录清理周期 |
| `gateway.rate-limit.permits-per-window` | `10` | 每客户端每窗口配额 |
| `gateway.rate-limit.window` | `1m` | 限流窗口长度 |
| `gateway.retry.max-attempts` | `3` | 最大尝试次数（含首次） |
| `gateway.retry.backoff` | `100ms` | 初始退避 |
| `gateway.retry.multiplier` | `2` | 退避倍率 |
| `gateway.retry.max-backoff` | `1s` | 退避上限 |
| `gateway.circuit-breaker.failure-threshold` | `5` | 连续失败熔断阈值 |
| `gateway.circuit-breaker.open-duration` | `10s` | 熔断打开时长 |
| `gateway.mock.supported-upstream-versions` | `v1,v2,v3` | 模拟上游开放的版本（收窄可模拟上游不支持某版本） |
| `gateway.mock.v2-latency` | `50ms` | 模拟上游成功场景延迟（放大并发窗口） |

---

## 8. 本地模拟上游

`MockUpspring` 位于 `/mock-upstream`：

- `POST /mock-upstream/payments`：通过请求头 `X-Upstream-Version`（v1/v2/v3）和
  `X-Mock-Scenario` 控制行为。场景：`success` / `missing-field` / `type-mismatch` /
  `malformed` / `timeout` / `error-500` / `error-502` / `error-400`。
- `GET /mock-upstream/admin/calls`：返回按场景统计的真实调用次数与最大在途数，
  测试用于断言“上游只被调用一次”。
- `POST /mock-upstream/admin/reset`：清零统计。

> 注：`X-Test-Scenario` 仅为联调/测试便利而透传，生产环境应在边缘层移除该头，
> 或把场景注入能力限制在测试 profile。

---

## 9. 测试覆盖

`src/test` 下共 11 个测试类、约 38 个用例：

- `VersionNormalizationIT`：v1/v2/v3 归一一致、默认版本、缺字段、类型不匹配、未知版本；
- `UpstreamVersionSupportIT`：上游不支持某版本（404）的端到端归一；
- `IdempotencyIT`：顺序重放、16 线程并发重复提交（上游仅 1 次、结果一致）、
  同键不同体冲突、失败结果缓存重放；
- `RateLimitIT`：按客户端配额超额 429、客户端间隔离；
- `UpstreamFailureIT`：超时重试、500/502/400 区分、坏响应不重试、连续失败熔断快速失败；
- `LoggingContentIT`：日志含 requestId / 命中版本 / 判定依据；
- 单元测试：`PaymentNormalizerTest`、`CircuitBreakerTest`、`RetryExecutorTest`、
  `IdempotencyStoreTest`。

```bash
mvn test                      # 全部
mvn test -Dtest=IdempotencyIT # 单个类
```
