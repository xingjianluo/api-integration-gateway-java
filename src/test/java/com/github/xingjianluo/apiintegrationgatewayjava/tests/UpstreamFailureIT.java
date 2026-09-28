package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.apiintegrationgatewayjava.ratelimit.FixedWindowRateLimiter;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.DefaultCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.upstream.read-timeout=300ms",
                "gateway.retry.max-attempts=3",
                "gateway.retry.backoff=20ms",
                "gateway.retry.max-backoff=50ms",
                "gateway.circuit-breaker.failure-threshold=3",
                "gateway.circuit-breaker.open-duration=60s",
                "gateway.rate-limit.permits-per-window=1000",
                "gateway.idempotency.ttl=1s"
        })
class UpstreamFailureIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private InMemoryIdempotencyStore idempotencyStore;
    @Autowired
    private FixedWindowRateLimiter rateLimiter;
    @Autowired
    private DefaultCircuitBreaker circuitBreaker;

    @BeforeEach
    void reset() throws InterruptedException {
        idempotencyStore.clear();
        rateLimiter.clear();
        circuitBreaker.reset();
        rest.postForEntity("/mock-upstream/admin/reset", null, Void.class);
        // 让上面清空后的幂等记录与 TTL 窗口干净隔离
        Thread.sleep(10);
    }

    @Test
    void timeout_is_normalized_to_UPSTREAM_TIMEOUT_and_retried() {
        ResponseEntity<JsonNode> resp = call("timeout", "key-timeout", "v1");
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        JsonNode error = resp.getBody();
        assertThat(error.get("errorCode").asText()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(error.get("retryable").asBoolean()).isTrue();

        JsonNode stats = upstreamCalls();
        assertThat(stats.get("callsByScenario").get("timeout").asInt())
                .as("超时是临时失败，应按最大次数重试").isEqualTo(3);
    }

    @Test
    void server_error_is_distinguished_from_bad_gateway() {
        ResponseEntity<JsonNode> e500 = call("error-500", "key-500", "v1");
        assertThat(e500.getBody().get("errorCode").asText()).isEqualTo("UPSTREAM_SERVER_ERROR");
        assertThat(e500.getBody().get("retryable").asBoolean()).isTrue();

        ResponseEntity<JsonNode> e502 = call("error-502", "key-502", "v1");
        assertThat(e502.getBody().get("errorCode").asText()).isEqualTo("UPSTREAM_BAD_GATEWAY");
    }

    @Test
    void client_error_is_not_retried() {
        ResponseEntity<JsonNode> resp = call("error-400", "key-400", "v1");
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(resp.getBody().get("errorCode").asText()).isEqualTo("UPSTREAM_BAD_REQUEST");
        assertThat(resp.getBody().get("retryable").asBoolean()).isFalse();

        JsonNode stats = upstreamCalls();
        assertThat(stats.get("callsByScenario").get("error-400").asInt())
                .as("非临时性失败不应重试").isEqualTo(1);
    }

    @Test
    void malformed_json_is_normalized_and_not_retried() {
        ResponseEntity<JsonNode> resp = call("malformed", "key-malformed", "v1");
        assertThat(resp.getBody().get("errorCode").asText()).isEqualTo("UPSTREAM_MALFORMED_RESPONSE");

        JsonNode stats = upstreamCalls();
        assertThat(stats.get("callsByScenario").get("malformed").asInt()).isEqualTo(1);
    }

    @Test
    void consecutive_failures_open_circuit_and_later_requests_fast_fail() {
        // 阈值 3：前 3 次失败后熔断打开
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<JsonNode> resp = call("error-500", "key-circuit-" + i, "v1");
            assertThat(resp.getBody().get("errorCode").asText()).isEqualTo("UPSTREAM_SERVER_ERROR");
        }

        ResponseEntity<JsonNode> fastFail = call("error-500", "key-circuit-blocked", "v1");
        assertThat(fastFail.getStatusCode().value()).isEqualTo(503);
        assertThat(fastFail.getBody().get("errorCode").asText()).isEqualTo("UPSTREAM_CIRCUIT_OPEN");

        // 熔断打开后连成功场景都不应打到上游 -> 不拖垮后续请求、也不再增加上游负担
        ResponseEntity<JsonNode> blockedSuccess = call("success", "key-circuit-success", "v1");
        assertThat(blockedSuccess.getBody().get("errorCode").asText()).isEqualTo("UPSTREAM_CIRCUIT_OPEN");

        JsonNode stats = upstreamCalls();
        // 每次 500 重试 3 次，共 9 次；之后两个请求被熔断拦截，不增加调用
        assertThat(stats.get("callsByScenario").get("error-500").asInt()).isEqualTo(9);
        assertThat(stats.get("callsByScenario").has("success")).isFalse();
    }

    private JsonNode upstreamCalls() {
        return rest.getForEntity("/mock-upstream/admin/calls", JsonNode.class).getBody();
    }

    private ResponseEntity<JsonNode> call(String scenario, String key, String version) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Client-Id", "fail-client");
        h.add("X-Api-Version", version);
        h.add("X-Test-Scenario", scenario);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", key + "-" + UUID.randomUUID());
        body.put("orderRef", key);
        body.put("amount", 100);
        body.put("currency", "USD");
        return rest.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
    }
}
