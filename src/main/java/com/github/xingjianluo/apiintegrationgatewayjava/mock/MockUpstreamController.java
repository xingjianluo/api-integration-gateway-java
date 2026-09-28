package com.github.xingjianluo.apiintegrationgatewayjava.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地模拟第三方上游：路径前缀 /mock-upstream，与网关应用共进程运行。
 * 通过请求头 X-Mock-Scenario 控制行为，便于测试覆盖上游的各类响应。
 *
 * <p>支持版本：X-Upstream-Version = v1 / v2 / v3，可用配置项
 * gateway.mock.supported-upstream-versions 收窄。
 *
 * <p>场景（X-Mock-Scenario）：
 * <pre>
 * success         200，按版本返回结构各异的正常支付结果
 * missing-field   200，返回体中故意缺少一个必需字段
 * type-mismatch   200，返回体中某字段类型错误
 * malformed       200，返回非 JSON 文本
 * timeout         延迟到超过网关读超时
 * error-500       500 + JSON 错误体
 * error-502       502 + JSON 错误体
 * error-400       400 + JSON 错误体
 * </pre>
 */
@RestController
@RequestMapping("/mock-upstream")
public class MockUpstreamController {

    public static final String SCENARIO_HEADER = "X-Mock-Scenario";
    public static final String VERSION_HEADER = "X-Upstream-Version";

    private final GatewayProperties properties;

    private final ConcurrentHashMap<String, AtomicInteger> callsByScenario = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicReference<Integer> maxInFlight = new AtomicReference<>(0);

    public MockUpstreamController(GatewayProperties properties) {
        this.properties = properties;
    }

    @PostMapping(path = "/payments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createPayment(@RequestBody(required = false) JsonNode body,
                                           HttpServletRequest request) throws InterruptedException {
        String scenario = headerOrDefault(request, SCENARIO_HEADER, "success");
        String version = headerOrDefault(request, VERSION_HEADER, "v1");
        callsByScenario.computeIfAbsent(scenario, k -> new AtomicInteger()).incrementAndGet();

        if (!properties.getMock().getSupportedUpstreamVersions().contains(version)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "upstream version not supported: " + version));
        }

        return switch (scenario) {
            case "success" -> withInFlight(() -> ResponseEntity.ok(successBody(version, body)));
            case "missing-field" -> ResponseEntity.ok(missingFieldBody(version));
            case "type-mismatch" -> ResponseEntity.ok(typeMismatchBody(version));
            case "malformed" -> ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("this is not json <<<");
            case "timeout" -> {
                Thread.sleep(Duration.ofSeconds(10).toMillis());
                yield ResponseEntity.ok(successBody(version, body));
            }
            case "error-500" -> ResponseEntity.status(500)
                    .body(Map.of("error", "upstream internal failure"));
            case "error-502" -> ResponseEntity.status(502)
                    .body(Map.of("error", "upstream bad gateway"));
            case "error-400" -> ResponseEntity.status(400)
                    .body(Map.of("error", "upstream rejected the request"));
            default -> ResponseEntity.status(500)
                    .body(Map.of("error", "unknown mock scenario: " + scenario));
        };
    }

    private ResponseEntity<Map<String, Object>> withInFlight(
            java.util.function.Supplier<ResponseEntity<Map<String, Object>>> responder) {
        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        try {
            // 成功场景保留一点可配置延迟，用于放大并发窗口
            Thread.sleep(properties.getMock().getV2Latency().toMillis());
            return responder.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(Map.of("error", "interrupted"));
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private Map<String, Object> successBody(String version, JsonNode body) {
        String orderRef = body != null && body.hasNonNull("orderRef") && body.get("orderRef").isTextual()
                ? body.get("orderRef").asText() : "unknown";
        String txnId = "MOCK-" + orderRef;
        Object amount = body != null && body.has("amount") ? body.get("amount").numberValue() : 0;
        String currency = body != null && body.hasNonNull("currency") && body.get("currency").isTextual()
                ? body.get("currency").asText() : "USD";
        return switch (version) {
            case "v1" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("txn_id", txnId);
                m.put("amount", amount);
                m.put("currency", currency);
                m.put("state", "OK");
                yield m;
            }
            case "v2" -> {
                Map<String, Object> money = new LinkedHashMap<>();
                money.put("value", amount);
                money.put("currencyCode", currency);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("paymentId", txnId);
                m.put("money", money);
                m.put("result", "SUCCESS");
                yield m;
            }
            default -> {
                Map<String, Object> amt = new LinkedHashMap<>();
                amt.put("value", amount);
                amt.put("currency", currency);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", txnId);
                m.put("amount", amt);
                m.put("paid", true);
                yield m;
            }
        };
    }

    private Map<String, Object> missingFieldBody(String version) {
        return switch (version) {
            case "v1" -> Map.of("amount", 100, "currency", "USD", "state", "OK");
            case "v2" -> Map.of("paymentId", "MOCK-missing", "result", "SUCCESS");
            default -> Map.of("id", "MOCK-missing", "paid", true);
        };
    }

    private Map<String, Object> typeMismatchBody(String version) {
        return switch (version) {
            case "v1" -> Map.of(
                    "txn_id", "MOCK-bad", "amount", "one-hundred",
                    "currency", "USD", "state", "OK");
            case "v2" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("paymentId", "MOCK-bad");
                m.put("money", "not-an-object");
                m.put("result", "SUCCESS");
                yield m;
            }
            default -> Map.of(
                    "id", "MOCK-bad",
                    "amount", Map.of("value", 100, "currency", "USD"),
                    "paid", "yes");
        };
    }

    @GetMapping("/admin/calls")
    public Map<String, Object> callStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("callsByScenario", new LinkedHashMap<>(callsByScenario));
        stats.put("maxInFlight", maxInFlight.get());
        return stats;
    }

    @PostMapping("/admin/reset")
    public Map<String, Object> reset() {
        callsByScenario.clear();
        inFlight.set(0);
        maxInFlight.set(0);
        return Map.of("reset", true);
    }

    private String headerOrDefault(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
