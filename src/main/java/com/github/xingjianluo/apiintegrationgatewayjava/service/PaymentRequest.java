package com.github.xingjianluo.apiintegrationgatewayjava.service;

import java.math.BigDecimal;

public record PaymentRequest(
        String idempotencyKey,
        String version,
        String orderRef,
        BigDecimal amount,
        String currency,
        String testScenario
) {
}
