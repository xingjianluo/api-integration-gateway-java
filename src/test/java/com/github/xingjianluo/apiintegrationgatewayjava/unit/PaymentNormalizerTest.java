package com.github.xingjianluo.apiintegrationgatewayjava.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.model.NormalizedPayment;
import com.github.xingjianluo.apiintegrationgatewayjava.model.PaymentStatus;
import com.github.xingjianluo.apiintegrationgatewayjava.version.ApiVersion;
import com.github.xingjianluo.apiintegrationgatewayjava.version.DefaultPaymentNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentNormalizerTest {

    private final DefaultPaymentNormalizer normalizer = new DefaultPaymentNormalizer();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void v1_full_mapping() throws Exception {
        JsonNode raw = mapper.readTree(
                "{\"txn_id\":\"T1\",\"amount\":12.5,\"currency\":\"CNY\",\"state\":\"PROCESSING\"}");
        NormalizedPayment p = normalizer.normalize(ApiVersion.V1, raw);
        assertThat(p.transactionId()).isEqualTo("T1");
        assertThat(p.amount()).isEqualByComparingTo("12.5");
        assertThat(p.currency()).isEqualTo("CNY");
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.sourceVersion()).isEqualTo("v1");
    }

    @Test
    void v2_nested_money_mapping() throws Exception {
        JsonNode raw = mapper.readTree(
                "{\"paymentId\":\"T2\",\"money\":{\"value\":200,\"currencyCode\":\"USD\"},\"result\":\"FAILED\"}");
        NormalizedPayment p = normalizer.normalize(ApiVersion.V2, raw);
        assertThat(p.transactionId()).isEqualTo("T2");
        assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.currency()).isEqualTo("USD");
    }

    @Test
    void v3_paid_null_means_pending() throws Exception {
        JsonNode raw = mapper.readTree(
                "{\"id\":\"T3\",\"amount\":{\"value\":1,\"currency\":\"EUR\"},\"paid\":null}");
        NormalizedPayment p = normalizer.normalize(ApiVersion.V3, raw);
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void v3_paid_false_means_failed() throws Exception {
        JsonNode raw = mapper.readTree(
                "{\"id\":\"T3\",\"amount\":{\"value\":1,\"currency\":\"EUR\"},\"paid\":false}");
        assertThat(normalizer.normalize(ApiVersion.V3, raw).status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void missing_field_is_rejected() throws Exception {
        JsonNode raw = mapper.readTree("{\"amount\":1,\"currency\":\"USD\",\"state\":\"OK\"}");
        assertThatThrownBy(() -> normalizer.normalize(ApiVersion.V1, raw))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getErrorCode())
                .isEqualTo(GatewayErrorCode.UPSTREAM_MISSING_FIELD);
    }

    @Test
    void wrong_type_is_rejected() throws Exception {
        JsonNode raw = mapper.readTree(
                "{\"txn_id\":\"T\",\"amount\":\"x\",\"currency\":\"USD\",\"state\":\"OK\"}");
        assertThatThrownBy(() -> normalizer.normalize(ApiVersion.V1, raw))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getErrorCode())
                .isEqualTo(GatewayErrorCode.UPSTREAM_TYPE_MISMATCH);
    }

    @Test
    void illegal_status_value_is_type_mismatch() throws Exception {
        JsonNode raw = mapper.readTree(
                "{\"txn_id\":\"T\",\"amount\":1,\"currency\":\"USD\",\"state\":\"WEIRD\"}");
        assertThatThrownBy(() -> normalizer.normalize(ApiVersion.V1, raw))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getErrorCode())
                .isEqualTo(GatewayErrorCode.UPSTREAM_TYPE_MISMATCH);
    }

    @Test
    void null_body_is_malformed() {
        assertThatThrownBy(() -> normalizer.normalize(ApiVersion.V1, null))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getErrorCode())
                .isEqualTo(GatewayErrorCode.UPSTREAM_MALFORMED_RESPONSE);
    }
}
