package com.github.xingjianluo.apiintegrationgatewayjava.upstream.mock;

import com.github.xingjianluo.apiintegrationgatewayjava.model.ApiVersion;
import com.github.xingjianluo.apiintegrationgatewayjava.model.MockMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地模拟第三方上游服务。
 *
 * <p>不同版本路径返回结构不一致的报文，通过 {@code X-Mock-Mode} 注入故障：
 * TIMEOUT / ERROR_503 / ERROR_500 / INTERNAL_ERROR / MISSING_FIELD / TYPE_MISMATCH。
 */
@RestController
@RequestMapping("/mock-upstream")
public class MockUpstreamController {

    private static final Logger log = LoggerFactory.getLogger(MockUpstreamController.class);

    private final UpstreamStats stats;

    public MockUpstreamController(UpstreamStats stats) {
        this.stats = stats;
    }

    @GetMapping({"/v1/orders/{orderId}", "/v2/orders/{orderId}", "/v3/orders/{orderId}"})
    public Object fetchOrder(@PathVariable String orderId,
                             @RequestHeader(value = "X-Mock-Mode", required = false) String modeHeader) {
        ApiVersion version = versionFromUri();
        MockMode mode = parseMode(modeHeader);
        // 任何模式下都计数，便于断言“真正打到上游几次”（含重试）。
        stats.recordInvocation(version);
        log.info("模拟上游被调用 orderId={} version={} mode={} totalInvocations={}",
                orderId, version, mode, stats.getTotal());

        return switch (mode) {
            case NORMAL -> ResponseEntity.ok(payload(orderId, version, false, false));
            case MISSING_FIELD -> ResponseEntity.ok(payload(orderId, version, true, false));
            case TYPE_MISMATCH -> ResponseEntity.ok(payload(orderId, version, false, true));
            case ERROR_503 -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "upstream busy"));
            case ERROR_500 -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "upstream boom"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "order not found"));
            case INTERNAL_ERROR -> throw new IllegalStateException("mock upstream internal failure");
            case TIMEOUT -> {
                try {
                    // 超过调用方读超时，触发 SocketTimeoutException。
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                yield ResponseEntity.ok(payload(orderId, version, false, false));
            }
        };
    }

    @GetMapping("/admin/stats")
    public Map<String, Object> statsView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("total", stats.getTotal());
        for (ApiVersion v : ApiVersion.values()) {
            view.put(v.name(), stats.get(v));
        }
        return view;
    }

    @PostMapping("/admin/reset")
    public Map<String, String> reset() {
        stats.reset();
        return Map.of("status", "ok");
    }

    private ApiVersion versionFromUri() {
        // 由三条显式路径映射，当前请求属性中拿到路径模板变量最直接的方式是统一参数，
        // 这里通过请求 URI 解析版本段。
        String uri = String.valueOf(
                org.springframework.web.context.request.RequestContextHolder
                        .currentRequestAttributes()
                        .getAttribute(
                                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST));
        for (ApiVersion v : ApiVersion.values()) {
            if (uri.contains(v.name().toLowerCase())) {
                return v;
            }
        }
        throw new IllegalStateException("无法识别上游版本路径: " + uri);
    }

    private MockMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return MockMode.NORMAL;
        }
        try {
            return MockMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知 X-Mock-Mode={}, 按 NORMAL 处理", raw);
            return MockMode.NORMAL;
        }
    }

    /**
     * 构造各版本异构报文；
     * omitField=true 删除一个归一必填字段，corrupt=true 写入错误类型的值。
     */
    private Map<String, Object> payload(String orderId, ApiVersion version,
                                        boolean omitField, boolean corrupt) {
        // 归一后的确定性业务数据（三个版本语义一致、结构不同）。
        // amount = 12.34 元；UNPAID；createdAt = 2026-01-02T03:04:05Z
        long millis = 1767323045000L;
        long seconds = millis / 1000L;
        return switch (version) {
            case V1 -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderId", orderId);
                m.put("userId", "user-1");
                m.put("money", corrupt ? "not-a-number" : "12.34");
                m.put("status", corrupt ? "9" : "0");
                m.put("createdTime", String.valueOf(seconds));
                if (omitField) {
                    m.remove("money");
                }
                yield m;
            }
            case V2 -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("order_no", orderId);
                m.put("user_id", "user-1");
                m.put("amount", corrupt ? "abc" : 1234); // 分
                m.put("currency", "CNY");
                m.put("status", corrupt ? "WEIRD" : "unpaid");
                m.put("created_at", millis);
                if (omitField) {
                    m.remove("currency");
                }
                yield m;
            }
            case V3 -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", orderId);
                m.put("customer", "user-1");
                Map<String, Object> amount = new LinkedHashMap<>();
                amount.put("currency", "CNY");
                amount.put("value", corrupt ? "bad-value" : "12.34");
                m.put("amount", amount);
                m.put("state", corrupt ? "UNKNOWN" : "CREATED");
                m.put("createdAt", corrupt ? "not-a-date" : "2026-01-02T03:04:05Z");
                if (omitField) {
                    amount.remove("currency");
                }
                yield m;
            }
        };
    }
}
