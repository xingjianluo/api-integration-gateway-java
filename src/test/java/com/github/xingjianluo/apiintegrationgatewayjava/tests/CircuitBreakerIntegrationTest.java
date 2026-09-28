package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 熔断 + 降级：连续临时失败触发熔断后，后续请求不再打上游，
 * 若该订单有最近成功结果则降级返回（degraded=true），保证服务可用。
 */
@TestPropertySource(properties = {
        "gateway.retry.max-attempts=1",
        "gateway.circuit-breaker.failure-threshold=4",
        "gateway.circuit-breaker.open-duration=30s",
        "gateway.rate-limit.permits-per-window=1000"
})
class CircuitBreakerIntegrationTest extends AbstractIntegrationTest {

    private JsonNode query(String orderId, String mode, String key) {
        var spec = http.post().uri("/api/gateway/orders/" + orderId + "/query")
                .header("X-Client-Id", "c-cb")
                .header("X-Api-Version", "v3");
        if (key != null) {
            spec.header("X-Idempotency-Key", key);
        }
        if (mode != null) {
            spec.header("X-Mock-Mode", mode);
        }
        return spec.retrieve().body(JsonNode.class);
    }

    @Test
    void opens_after_consecutive_failures_and_serves_cached_fallback() {
        String orderId = "cb-order";

        // 1. 先有一次成功，建立降级缓存。
        JsonNode ok = query(orderId, "NORMAL", java.util.UUID.randomUUID().toString());
        assertTrue(ok.path("success").asBoolean());
        assertFalse(ok.path("degraded").asBoolean());
        int invocationsAfterSuccess = upstreamTotalInvocations();

        // 2. 连续失败达到阈值（4 次都实际打上游并返回 503），第 4 次失败记录后熔断打开。
        for (int i = 0; i < 4; i++) {
            assertThrows(org.springframework.web.client.HttpServerErrorException.class,
                    () -> query(orderId, "ERROR_503", java.util.UUID.randomUUID().toString()));
        }

        // 3. 熔断打开后再次请求：不打上游，直接降级返回缓存。
        JsonNode degraded = query(orderId, "ERROR_503", java.util.UUID.randomUUID().toString());
        assertTrue(degraded.path("success").asBoolean(), "有缓存时应降级成功");
        assertTrue(degraded.path("degraded").asBoolean(), "必须标记 degraded");
        assertEquals(ok.path("order").toString(), degraded.path("order").toString(),
                "降级返回最近成功数据");

        // 熔断打开期间不得继续打上游（成功 1 次 + 触发阈值的 4 次失败尝试）。
        assertEquals(invocationsAfterSuccess + 4, upstreamTotalInvocations(),
                "熔断打开后不应再调用上游");
    }

    @Test
    void open_circuit_without_cache_is_rejected_as_unavailable() {
        // 该订单从未成功过，触发熔断后无降级数据 -> 503 UPSTREAM_UNAVAILABLE
        for (int i = 0; i < 4; i++) {
            assertThrows(org.springframework.web.client.HttpServerErrorException.class,
                    () -> query("never-seen", "ERROR_503", java.util.UUID.randomUUID().toString()));
        }
        org.springframework.web.client.HttpServerErrorException ex = assertThrows(
                org.springframework.web.client.HttpServerErrorException.class,
                () -> query("never-seen", "NORMAL", java.util.UUID.randomUUID().toString()));
        assertEquals(503, ex.getStatusCode().value());
        assertTrue(ex.getResponseBodyAsString().contains("UPSTREAM_UNAVAILABLE"));
    }
}
