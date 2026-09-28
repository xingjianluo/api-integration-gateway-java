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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VersionNormalizationIT {

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
    void all_three_versions_normalize_to_same_external_model() {
        ResponseEntity<JsonNode> v1 = post("v1", "success", "ord-v1", "key-v1");
        ResponseEntity<JsonNode> v2 = post("v2", "success", "ord-v2", "key-v2");
        ResponseEntity<JsonNode> v3 = post("v3", "success", "ord-v3", "key-v3");

        assertNormalized(v1, "MOCK-ord-v1", "v1");
        assertNormalized(v2, "MOCK-ord-v2", "v2");
        assertNormalized(v3, "MOCK-ord-v3", "v3");
    }

    @Test
    void default_version_is_used_when_header_absent() {
        ResponseEntity<JsonNode> resp = post(null, "success", "ord-default", "key-default");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("payment").get("sourceVersion").asText()).isEqualTo("v1");
        assertThat(resp.getBody().get("idempotentReplay").asBoolean()).isFalse();
    }

    @Test
    void missing_field_is_rejected_with_distinct_code() {
        ResponseEntity<JsonNode> resp = post("v2", "missing-field", "ord-missing", "key-missing");
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        JsonNode error = resp.getBody();
        assertThat(error.get("errorCode").asText()).isEqualTo("UPSTREAM_MISSING_FIELD");
        assertThat(error.get("retryable").asBoolean()).isFalse();
        assertThat(error.get("message").asText()).contains("money");
        assertThat(error.get("requestId").asText()).isNotBlank();
    }

    @Test
    void type_mismatch_is_rejected_with_distinct_code() {
        ResponseEntity<JsonNode> resp = post("v3", "type-mismatch", "ord-badtype", "key-badtype");
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        JsonNode error = resp.getBody();
        assertThat(error.get("errorCode").asText()).isEqualTo("UPSTREAM_TYPE_MISMATCH");
        assertThat(error.get("message").asText()).contains("paid");
    }

    @Test
    void unknown_version_is_rejected_as_unsupported() {
        ResponseEntity<JsonNode> resp = post("v9", "success", "ord-v9", "key-v9");
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        assertThat(resp.getBody().get("errorCode").asText()).isEqualTo("VERSION_UNSUPPORTED");
    }

    private void assertNormalized(ResponseEntity<JsonNode> resp, String txnId, String version) {
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode payment = resp.getBody().get("payment");
        assertThat(payment.get("transactionId").asText()).isEqualTo(txnId);
        assertThat(payment.get("amount").decimalValue()).isEqualByComparingTo("100");
        assertThat(payment.get("currency").asText()).isEqualTo("USD");
        assertThat(payment.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(payment.get("sourceVersion").asText()).isEqualTo(version);
    }

    private ResponseEntity<JsonNode> post(String version, String scenario, String orderRef, String key) {
        return post(version, scenario, orderRef, key,
                headers(version, scenario, key, "client-" + orderRef));
    }

    private ResponseEntity<JsonNode> post(String version, String scenario, String orderRef,
                                          String key, HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", key);
        body.put("orderRef", orderRef);
        body.put("amount", 100);
        body.put("currency", "USD");
        return rest.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private HttpHeaders headers(String version, String scenario, String key, String clientId) {
        HttpHeaders h = new HttpHeaders();
        if (clientId != null) {
            h.add("X-Client-Id", clientId);
        }
        if (version != null) {
            h.add("X-Api-Version", version);
        }
        if (scenario != null) {
            h.add("X-Test-Scenario", scenario);
        }
        return h;
    }
}
