package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 按客户端固定窗口限流：超过配额明确 429 RATE_LIMITED；不同客户端互不影响。
 */
@TestPropertySource(properties = {
        "gateway.rate-limit.permits-per-window=2",
        "gateway.rate-limit.window=1m"
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    private JsonNode query(String client) {
        return http.post()
                .uri("/api/gateway/orders/rl-" + client + "/query")
                .header("X-Client-Id", client)
                .header("X-Idempotency-Key", java.util.UUID.randomUUID().toString())
                .header("X-Api-Version", "v3")
                .retrieve().body(JsonNode.class);
    }

    @Test
    void requests_over_quota_are_rejected() {
        assertTrue(query("alpha").path("success").asBoolean());
        assertTrue(query("alpha").path("success").asBoolean());

        HttpClientErrorException.TooManyRequests ex = assertThrows(
                HttpClientErrorException.TooManyRequests.class, () -> query("alpha"));
        assertEquals(429, ex.getStatusCode().value());
        assertTrue(ex.getResponseBodyAsString().contains("RATE_LIMITED"));
    }

    @Test
    void quotas_are_isolated_per_client() {
        assertTrue(query("beta").path("success").asBoolean());
        assertTrue(query("beta").path("success").asBoolean());
        // 另一个客户端不受影响
        assertTrue(query("gamma").path("success").asBoolean());
        assertThrows(HttpClientErrorException.TooManyRequests.class, () -> query("beta"));
        assertTrue(query("gamma").path("success").asBoolean());
    }
}
