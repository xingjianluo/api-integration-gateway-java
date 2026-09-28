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
                "gateway.rate-limit.permits-per-window=3",
                "gateway.rate-limit.window=1m"
        })
class RateLimitIT {

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
        rest.postForEntity("/mock-upstream/admin/reset", null, Void.class);
    }

    @Test
    void calls_beyond_quota_are_rejected_with_429() {
        String client = "rl-client";
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<JsonNode> ok = post(client, "key-rl-" + i);
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
        }
        ResponseEntity<JsonNode> rejected = post(client, "key-rl-4");
        assertThat(rejected.getStatusCode().value()).isEqualTo(429);
        JsonNode error = rejected.getBody();
        assertThat(error.get("errorCode").asText()).isEqualTo("RATE_LIMITED");
        assertThat(error.get("retryable").asBoolean()).isFalse();
        assertThat(error.get("requestId").asText()).isNotBlank();
    }

    @Test
    void quotas_are_isolated_per_client() {
        assertThat(post("client-a", "key-a-1").getStatusCode().value()).isEqualTo(200);
        assertThat(post("client-a", "key-a-2").getStatusCode().value()).isEqualTo(200);
        assertThat(post("client-a", "key-a-3").getStatusCode().value()).isEqualTo(200);
        assertThat(post("client-a", "key-a-4").getStatusCode().value()).isEqualTo(429);

        // 另一个客户端有独立配额
        assertThat(post("client-b", "key-b-1").getStatusCode().value()).isEqualTo(200);
        assertThat(post("client-b", "key-b-2").getStatusCode().value()).isEqualTo(200);
    }

    private ResponseEntity<JsonNode> post(String client, String key) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Client-Id", client);
        h.add("X-Api-Version", "v1");
        h.add("X-Test-Scenario", "success");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", key + "-" + UUID.randomUUID());
        body.put("orderRef", key);
        body.put("amount", 100);
        body.put("currency", "USD");
        return rest.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
    }
}
