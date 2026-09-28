package com.github.xingjianluo.apiintegrationgatewayjava.model;

import java.math.BigDecimal;

public record NormalizedPayment(
        String transactionId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String sourceVersion
) {
}
