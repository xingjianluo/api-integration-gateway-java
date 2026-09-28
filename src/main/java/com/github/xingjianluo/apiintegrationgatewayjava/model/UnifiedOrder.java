package com.github.xingjianluo.apiintegrationgatewayjava.model;

import java.math.BigDecimal;

/**
 * 对外统一订单模型：无论命中哪个上游版本，结构都一致。
 */
public record UnifiedOrder(
        String orderId,
        String userId,
        BigDecimal amount,
        String currency,
        OrderStatus status,
        Long createdAtEpochMilli
) {
}
