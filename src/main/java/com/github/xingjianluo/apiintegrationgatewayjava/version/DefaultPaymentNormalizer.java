package com.github.xingjianluo.apiintegrationgatewayjava.version;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.model.NormalizedPayment;
import com.github.xingjianluo.apiintegrationgatewayjava.model.PaymentStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 将不同上游版本的异构支付响应归一为统一对外模型。
 *
 * <p>各版本字段结构：
 * <ul>
 *   <li>v1: txn_id / amount / currency / state(OK|PROCESSING|FAIL)</li>
 *   <li>v2: paymentId / money{value,currencyCode} / result(SUCCESS|PENDING|FAILED)</li>
 *   <li>v3: id / amount{value,currency} / paid(true|false|null)</li>
 * </ul>
 * 字段缺失抛 UPSTREAM_MISSING_FIELD，字段类型/取值不合法抛 UPSTREAM_TYPE_MISMATCH，
 * 版本不支持抛 VERSION_UNSUPPORTED，均不会被静默跳过。
 */
@Component
public class DefaultPaymentNormalizer implements PaymentNormalizer {

    @Override
    public NormalizedPayment normalize(ApiVersion version, JsonNode raw) {
        if (version == null) {
            throw new GatewayException(GatewayErrorCode.VERSION_UNSUPPORTED,
                    "无法判定上游接口版本");
        }
        if (raw == null || raw.isNull() || !raw.isObject()) {
            throw new GatewayException(GatewayErrorCode.UPSTREAM_MALFORMED_RESPONSE,
                    "上游响应不是合法的 JSON 对象 (version=" + version.code() + ")");
        }
        NormalizedPayment payment = switch (version) {
            case V1 -> normalizeV1(raw);
            case V2 -> normalizeV2(raw);
            case V3 -> normalizeV3(raw);
        };
        return payment;
    }

    private NormalizedPayment normalizeV1(JsonNode raw) {
        String txnId = requireText(raw, "txn_id", "v1");
        BigDecimal amount = requireAmount(raw, "amount", "v1");
        String currency = requireText(raw, "currency", "v1");
        PaymentStatus status = switchV1Status(raw);
        return new NormalizedPayment(txnId, amount, currency, status, ApiVersion.V1.code());
    }

    private NormalizedPayment normalizeV2(JsonNode raw) {
        String txnId = requireText(raw, "paymentId", "v2");
        JsonNode money = requireObject(raw, "money", "v2");
        BigDecimal amount = requireAmount(money, "value", "v2.money");
        String currency = requireText(money, "currencyCode", "v2.money");
        PaymentStatus status = switchNamedStatus(raw, "result", "v2");
        return new NormalizedPayment(txnId, amount, currency, status, ApiVersion.V2.code());
    }

    private NormalizedPayment normalizeV3(JsonNode raw) {
        String txnId = requireText(raw, "id", "v3");
        JsonNode amountNode = requireObject(raw, "amount", "v3");
        BigDecimal amount = requireAmount(amountNode, "value", "v3.amount");
        String currency = requireText(amountNode, "currency", "v3.amount");
        PaymentStatus status = switchV3Paid(raw);
        return new NormalizedPayment(txnId, amount, currency, status, ApiVersion.V3.code());
    }

    private PaymentStatus switchV1Status(JsonNode raw) {
        JsonNode node = raw.get("state");
        if (node == null || node.isNull()) {
            throw missingField("state", "v1");
        }
        if (!node.isTextual()) {
            throw typeMismatch("state", "string(OK|PROCESSING|FAIL)", node);
        }
        return switch (node.asText()) {
            case "OK" -> PaymentStatus.SUCCESS;
            case "PROCESSING" -> PaymentStatus.PENDING;
            case "FAIL" -> PaymentStatus.FAILED;
            default -> throw typeMismatch("state", "string(OK|PROCESSING|FAIL)", node);
        };
    }

    private PaymentStatus switchNamedStatus(JsonNode raw, String field, String scope) {
        JsonNode node = raw.get(field);
        if (node == null || node.isNull()) {
            throw missingField(field, scope);
        }
        if (!node.isTextual()) {
            throw typeMismatch(field, "string(SUCCESS|PENDING|FAILED)", node);
        }
        return switch (node.asText()) {
            case "SUCCESS" -> PaymentStatus.SUCCESS;
            case "PENDING" -> PaymentStatus.PENDING;
            case "FAILED" -> PaymentStatus.FAILED;
            default -> throw typeMismatch(field, "string(SUCCESS|PENDING|FAILED)", node);
        };
    }

    private PaymentStatus switchV3Paid(JsonNode raw) {
        JsonNode node = raw.get("paid");
        if (node == null) {
            throw missingField("paid", "v3");
        }
        if (node.isNull()) {
            return PaymentStatus.PENDING;
        }
        if (!node.isBoolean()) {
            throw typeMismatch("paid", "boolean|null", node);
        }
        return node.asBoolean() ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
    }

    private String requireText(JsonNode parent, String field, String scope) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw missingField(field, scope);
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw typeMismatch(field, "非空字符串", node);
        }
        return node.asText();
    }

    private BigDecimal requireAmount(JsonNode parent, String field, String scope) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw missingField(field, scope);
        }
        if (!node.isNumber()) {
            throw typeMismatch(field, "number", node);
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            throw typeMismatch(field, "number", node);
        }
    }

    private JsonNode requireObject(JsonNode parent, String field, String scope) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            throw missingField(field, scope);
        }
        if (!node.isObject()) {
            throw typeMismatch(field, "object", node);
        }
        return node;
    }

    private GatewayException missingField(String field, String scope) {
        return new GatewayException(GatewayErrorCode.UPSTREAM_MISSING_FIELD,
                "上游响应缺少必需字段: " + scope + "." + field);
    }

    private GatewayException typeMismatch(String field, String expected, JsonNode actual) {
        String actualType = actual == null ? "missing" : actual.getNodeType().name().toLowerCase();
        return new GatewayException(GatewayErrorCode.UPSTREAM_TYPE_MISMATCH,
                "上游响应字段类型不匹配: " + field + ", 期望 " + expected
                        + ", 实际类型 " + actualType + ", 原始值 " + actual);
    }
}
