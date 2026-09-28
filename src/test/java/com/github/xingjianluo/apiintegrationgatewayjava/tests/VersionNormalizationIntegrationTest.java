package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多版本归一：不同结构的报文统一成同一对外模型；
 * 缺字段 / 类型不匹配 / 版本不支持都必须被明确区分拒绝，不静默跳过。
 */
class VersionNormalizationIntegrationTest extends AbstractIntegrationTest {

    private JsonNode query(String orderId, String version, String mockMode) {
        var spec = http.post()
                .uri("/api/gateway/orders/" + orderId + "/query")
                .header("X-Client-Id", "c-normal");
        if (version != null) {
            spec.header("X-Api-Version", version);
        }
        if (mockMode != null) {
            spec.header("X-Mock-Mode", mockMode);
        }
        return spec.retrieve().body(JsonNode.class);
    }

    @Test
    void v1_v2_v3_normalize_to_same_model() {
        JsonNode v1 = query("o-1", "v1", null);
        JsonNode v2 = query("o-1", "v2", null);
        JsonNode v3 = query("o-1", "v3", null);

        for (JsonNode r : new JsonNode[]{v1, v2, v3}) {
            assertTrue(r.path("success").asBoolean());
            JsonNode o = r.path("order");
            assertEquals("o-1", o.path("orderId").asText());
            assertEquals("user-1", o.path("userId").asText());
            assertEquals("12.34", o.path("amount").asText());
            assertEquals("CNY", o.path("currency").asText());
            assertEquals("UNPAID", o.path("status").asText());
            assertEquals(1767323045000L, o.path("createdAtEpochMilli").asLong());
        }
        // 不同版本，语义完全一致。
        assertEquals(v1.path("order").toString(), v2.path("order").toString());
        assertEquals(v2.path("order").toString(), v3.path("order").toString());
    }

    @Test
    void default_version_is_used_when_header_absent() {
        JsonNode r = query("o-2", null, null);
        assertTrue(r.path("success").asBoolean());
        assertEquals("V3", r.path("apiVersion").asText());
    }

    @Test
    void missing_field_is_rejected_with_distinct_reason() {
        JsonNode r = query("o-3", "v2", "MISSING_FIELD");
        assertFalse(r.path("success").asBoolean());
        assertEquals("MISSING_FIELD", r.path("rejectReason").asText());
        assertTrue(r.path("message").asText().contains("currency"));
    }

    @Test
    void type_mismatch_is_rejected_with_distinct_reason() {
        JsonNode r = query("o-4", "v1", "TYPE_MISMATCH");
        assertFalse(r.path("success").asBoolean());
        assertEquals("FIELD_TYPE_MISMATCH", r.path("rejectReason").asText());
        // 缺字段与类型不匹配原因必须可区分
        assertNotEquals("MISSING_FIELD", r.path("rejectReason").asText());
    }

    @Test
    void unsupported_version_is_rejected_before_upstream_call() {
        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class,
                () -> query("o-5", "v9", null));
        assertEquals(HttpStatusCode.valueOf(400), ex.getStatusCode());
        assertTrue(ex.getResponseBodyAsString().contains("VERSION_UNSUPPORTED"));
        // 版本不支持时不得调用上游。
        assertEquals(0, upstreamTotalInvocations());
    }
}
