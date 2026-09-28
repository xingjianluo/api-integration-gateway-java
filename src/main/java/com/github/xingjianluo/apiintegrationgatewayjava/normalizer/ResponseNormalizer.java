package com.github.xingjianluo.apiintegrationgatewayjava.normalizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.model.ApiVersion;
import com.github.xingjianluo.apiintegrationgatewayjava.model.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.model.OrderStatus;
import com.github.xingjianluo.apiintegrationgatewayjava.model.RejectReason;
import com.github.xingjianluo.apiintegrationgatewayjava.model.UnifiedOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 将不同上游版本的异构报文归一为 {@link UnifiedOrder}。
 *
 * <p>字段契约：
 * <ul>
 *   <li>V1：{code orderId/userId/money(元,字符串)/status(数字)/createdTime(秒)}</li>
 *   <li>V2：{code order_no/user_id/amount(分,数字)/currency/status(小写字串)/created_at(毫秒)}</li>
 *   <li>V3：{code id/customer/amount(currency=code,value=元)/state(code)/createdAt(ISO-8601)}</li>
 * </ul>
 *
 * <p>缺字段 -> {@link RejectReason#MISSING_FIELD}；字段存在但类型/取值无法转换 ->
 * {@link RejectReason#FIELD_TYPE_MISMATCH}。绝不静默跳过。
 */
@Component
public class ResponseNormalizer {

    public UnifiedOrder normalize(JsonNode body, ApiVersion version, String requestId) {
        if (body == null || body.isNull() || !body.isObject()) {
            throw reject(RejectReason.FIELD_TYPE_MISMATCH, version, requestId,
                    "响应体不是 JSON 对象");
        }
        return switch (version) {
            case V1 -> normalizeV1(body, requestId);
            case V2 -> normalizeV2(body, requestId);
            case V3 -> normalizeV3(body, requestId);
        };
    }

    private UnifiedOrder normalizeV1(JsonNode body, String requestId) {
        String orderId = text(body, "orderId", ApiVersion.V1, requestId);
        String userId = text(body, "userId", ApiVersion.V1, requestId);
        // money：以“元”表示的字符串
        JsonNode moneyNode = requireField(body, "money", ApiVersion.V1, requestId);
        if (!moneyNode.isTextual()) {
            throw mismatch(ApiVersion.V1, requestId, "money", "期望字符串(元)");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(moneyNode.asText().trim());
        } catch (NumberFormatException e) {
            throw mismatch(ApiVersion.V1, requestId, "money", "无法解析为十进制金额");
        }
        // status：0=未支付 1=已支付 2=已退款
        JsonNode statusNode = requireField(body, "status", ApiVersion.V1, requestId);
        OrderStatus status = switch (statusNode.asText()) {
            case "0" -> OrderStatus.UNPAID;
            case "1" -> OrderStatus.PAID;
            case "2" -> OrderStatus.REFUNDED;
            default -> throw mismatch(ApiVersion.V1, requestId, "status", "只允许 0/1/2");
        };
        // createdTime：秒级时间戳
        JsonNode createdNode = requireField(body, "createdTime", ApiVersion.V1, requestId);
        long createdMillis;
        try {
            createdMillis = Long.parseLong(createdNode.asText().trim()) * 1000L;
        } catch (NumberFormatException e) {
            throw mismatch(ApiVersion.V1, requestId, "createdTime", "无法解析为秒级时间戳");
        }
        return new UnifiedOrder(orderId, userId, amount, "CNY", status, createdMillis);
    }

    private UnifiedOrder normalizeV2(JsonNode body, String requestId) {
        String orderId = text(body, "order_no", ApiVersion.V2, requestId);
        String userId = text(body, "user_id", ApiVersion.V2, requestId);
        // amount：以“分”表示的整数
        JsonNode amountNode = requireField(body, "amount", ApiVersion.V2, requestId);
        if (!amountNode.canConvertToLong() || !amountNode.isIntegralNumber()) {
            throw mismatch(ApiVersion.V2, requestId, "amount", "期望整数(分)");
        }
        BigDecimal amount = BigDecimal.valueOf(amountNode.asLong())
                .movePointLeft(2);
        String currency = text(body, "currency", ApiVersion.V2, requestId);
        JsonNode statusNode = requireField(body, "status", ApiVersion.V2, requestId);
        OrderStatus status = switch (statusNode.asText().trim().toUpperCase()) {
            case "PENDING", "UNPAID" -> OrderStatus.UNPAID;
            case "PAID" -> OrderStatus.PAID;
            case "REFUNDED" -> OrderStatus.REFUNDED;
            default -> throw mismatch(ApiVersion.V2, requestId, "status", "未知状态码");
        };
        JsonNode createdNode = requireField(body, "created_at", ApiVersion.V2, requestId);
        if (!createdNode.isIntegralNumber()) {
            throw mismatch(ApiVersion.V2, requestId, "created_at", "期望毫秒时间戳");
        }
        return new UnifiedOrder(orderId, userId, amount, currency, status, createdNode.asLong());
    }

    private UnifiedOrder normalizeV3(JsonNode body, String requestId) {
        String orderId = text(body, "id", ApiVersion.V3, requestId);
        String userId = text(body, "customer", ApiVersion.V3, requestId);
        // amount：{currency, value}，value 为字符串形式的“元”
        JsonNode amountNode = requireField(body, "amount", ApiVersion.V3, requestId);
        if (!amountNode.isObject()) {
            throw mismatch(ApiVersion.V3, requestId, "amount", "期望对象 {currency,value}");
        }
        String currency = text(amountNode, "currency", ApiVersion.V3, requestId);
        JsonNode valueNode = requireField(amountNode, "value", ApiVersion.V3, requestId);
        if (!valueNode.isTextual() && !valueNode.isNumber()) {
            throw mismatch(ApiVersion.V3, requestId, "amount.value", "期望数值(元)");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(valueNode.asText().trim());
        } catch (NumberFormatException e) {
            throw mismatch(ApiVersion.V3, requestId, "amount.value", "无法解析为十进制金额");
        }
        // state：CREATED/PAID/REFUNDED
        JsonNode stateNode = requireField(body, "state", ApiVersion.V3, requestId);
        OrderStatus status = switch (stateNode.asText().trim().toUpperCase()) {
            case "CREATED", "UNPAID" -> OrderStatus.UNPAID;
            case "PAID" -> OrderStatus.PAID;
            case "REFUNDED" -> OrderStatus.REFUNDED;
            default -> throw mismatch(ApiVersion.V3, requestId, "state", "未知状态码");
        };
        // createdAt：ISO-8601
        JsonNode createdNode = requireField(body, "createdAt", ApiVersion.V3, requestId);
        long createdMillis;
        try {
            createdMillis = java.time.OffsetDateTime.parse(createdNode.asText().trim())
                    .toInstant().toEpochMilli();
        } catch (Exception e) {
            throw mismatch(ApiVersion.V3, requestId, "createdAt", "无法解析为 ISO-8601 时间");
        }
        return new UnifiedOrder(orderId, userId, amount, currency, status, createdMillis);
    }

    private JsonNode requireField(JsonNode body, String field, ApiVersion version, String requestId) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            throw reject(RejectReason.MISSING_FIELD, version, requestId,
                    "缺少必填字段: " + field);
        }
        return node;
    }

    private String text(JsonNode body, String field, ApiVersion version, String requestId) {
        JsonNode node = requireField(body, field, version, requestId);
        if (!node.isTextual() || node.asText().isBlank()) {
            throw mismatch(version, requestId, field, "期望非空字符串");
        }
        return node.asText();
    }

    private GatewayException mismatch(ApiVersion version, String requestId, String field, String detail) {
        return reject(RejectReason.FIELD_TYPE_MISMATCH, version, requestId,
                "字段[" + field + "]类型或取值不匹配: " + detail);
    }

    private GatewayException reject(RejectReason reason, ApiVersion version, String requestId, String message) {
        return new GatewayException(null, reason, requestId,
                "[v=" + version + "] " + message, null);
    }
}
