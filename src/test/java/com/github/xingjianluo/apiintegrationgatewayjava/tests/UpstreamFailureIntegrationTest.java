package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpServerErrorException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上游各类失败的对外行为：
 * - 临时失败（超时/5xx/内部异常）按退避重试，耗尽后归为 UPSTREAM_UNAVAILABLE；
 * - 404 为确定性失败，不重试，原因可区分；
 * - 不向外透出上游原始异常类型/堆栈。
 */
@TestPropertySource(properties = {
        "gateway.retry.max-attempts=2",
        "gateway.retry.backoff=10ms",
        "gateway.upstream.read-timeout=200ms",
        "gateway.circuit-breaker.failure-threshold=99",
        "gateway.circuit-breaker.open-duration=1s",
        "gateway.rate-limit.permits-per-window=1000"
})
class UpstreamFailureIntegrationTest extends AbstractIntegrationTest {

    /** 走异常通道（重试耗尽）。 */
    private JsonNode callExpectingUnavailable(String orderId, String mode) {
        HttpServerErrorException ex = assertThrows(HttpServerErrorException.class, () ->
                http.post().uri("/api/gateway/orders/" + orderId + "/query")
                        .header("X-Client-Id", "c-fail")
                        .header("X-Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .header("X-Api-Version", "v3")
                        .header("X-Mock-Mode", mode)
                        .retrieve().body(JsonNode.class));
        assertEquals(503, ex.getStatusCode().value());
        String body = ex.getResponseBodyAsString();
        assertTrue(body.contains("UPSTREAM_UNAVAILABLE"), "应归一为 UPSTREAM_UNAVAILABLE: " + body);
        assertFalse(body.contains("SocketTimeout"), "不得透出上游原始异常: " + body);
        assertFalse(body.contains("IllegalState"), "不得透出上游原始异常: " + body);
        return null;
    }

    @Test
    void timeout_is_retried_then_classified_unavailable() {
        callExpectingUnavailable("o-timeout", "TIMEOUT");
        assertEquals(2, upstreamTotalInvocations(), "超时应重试到最大次数");
    }

    @Test
    void error_503_is_retried_then_classified_unavailable() {
        callExpectingUnavailable("o-503", "ERROR_503");
        assertEquals(2, upstreamTotalInvocations(), "5xx 临时失败应重试");
    }

    @Test
    void internal_error_is_retried_then_classified_unavailable() {
        callExpectingUnavailable("o-internal", "INTERNAL_ERROR");
        assertEquals(2, upstreamTotalInvocations(), "内部异常应重试");
    }

    @Test
    void not_found_is_deterministic_and_not_retried() {
        JsonNode r = http.post().uri("/api/gateway/orders/o-404/query")
                .header("X-Client-Id", "c-fail")
                .header("X-Idempotency-Key", java.util.UUID.randomUUID().toString())
                .header("X-Api-Version", "v2")
                .header("X-Mock-Mode", "NOT_FOUND")
                .retrieve().body(JsonNode.class);
        assertFalse(r.path("success").asBoolean());
        assertEquals("UPSTREAM_ORDER_NOT_FOUND", r.path("rejectReason").asText());
        assertEquals(1, upstreamTotalInvocations(), "404 不应重试");
    }

    @Test
    void transient_failure_is_not_persisted_so_later_same_key_can_succeed() {
        String key = java.util.UUID.randomUUID().toString();
        assertThrows(HttpServerErrorException.class, () ->
                http.post().uri("/api/gateway/orders/o-recover/query")
                        .header("X-Client-Id", "c-fail").header("X-Idempotency-Key", key)
                        .header("X-Api-Version", "v3").header("X-Mock-Mode", "ERROR_503")
                        .retrieve().body(JsonNode.class));
        // 上游恢复后，同一幂等键可以成功（临时失败不固化）。
        JsonNode ok = http.post().uri("/api/gateway/orders/o-recover/query")
                .header("X-Client-Id", "c-fail").header("X-Idempotency-Key", key)
                .header("X-Api-Version", "v3").header("X-Mock-Mode", "NORMAL")
                .retrieve().body(JsonNode.class);
        assertTrue(ok.path("success").asBoolean());
    }
}
