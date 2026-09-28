package com.github.xingjianluo.apiintegrationgatewayjava.service;

import com.github.xingjianluo.apiintegrationgatewayjava.model.NormalizedPayment;

public record PaymentResponse(
        NormalizedPayment payment,
        boolean idempotentReplay
) {
}
