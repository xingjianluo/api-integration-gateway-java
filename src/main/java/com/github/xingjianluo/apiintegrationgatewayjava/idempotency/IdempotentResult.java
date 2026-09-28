package com.github.xingjianluo.apiintegrationgatewayjava.idempotency;

import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;

public record IdempotentResult<T>(
        T value,
        GatewayException error,
        boolean replay
) {

    public static <T> IdempotentResult<T> success(T value, boolean replay) {
        return new IdempotentResult<>(value, null, replay);
    }

    public static <T> IdempotentResult<T> failure(GatewayException error, boolean replay) {
        return new IdempotentResult<>(null, error, replay);
    }
}
