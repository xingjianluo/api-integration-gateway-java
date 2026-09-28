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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关自身支持 v1/v2/v3，但模拟第三方上游通过配置只开放 v1。
 * 调用 v2/v3 时上游返回 404，网关将其归一为 VERSION_UNSUPPORTED，
 * 与“字段缺失/类型不匹配”等拒绝原因区分开。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.mock.supported-upstream-versions[0]=v1",
                "gateway.rate-limit.permits-per-window=1000"
        })
class UpstreamVersionSupportIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private InMemoryIdempotencyStore idempotencyStore;
    @Autowired
    private FixedWindowRateLimiter rateLimiter;
    @Autowired
    private DefaultCircuitBreaker circuitBreaker;

    @BeforeEach
    void reset() {
        idempotencyStore.clear();
        rateLimiter.clear();
        circuitBreaker.reset();
    }

    @Test
    void version_supported_by_upstream_succeeds() {
        ResponseEntity<JsonNode> resp = post("v1", "key-us-ok");
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody().get("payment").get("sourceVersion").asText()).isEqualTo("v1");
    }

    @Test
    void version_rejected_by_upstream_is_normalized_to_VERSION_UNSUPPORTED() {
        ResponseEntity<JsonNode> resp = post("v2", "key-us-v2");
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        JsonNode error = resp.getBody();
        assertThat(error.get("errorCode").asText()).isEqualTo("VERSION_UNSUPPORTED");
        assertThat(error.get("message").asText()).contains("404");
        assertThat(error.get("requestId").asText()).isNotBlank();
    }

    private ResponseEntity<JsonNode> post(String version, String key) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Client-Id", "us-client");
        h.add("X-Api-Version", version);
        h.add("X-Test-Scenario", "success");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", key);
        body.put("orderRef", key);
        body.put("amount", 100);
        body.put("currency", "USD");
        return rest.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
    }
}
