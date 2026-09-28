package com.github.xingjianluo.apiintegrationgatewayjava.core;

public enum GatewayErrorCode {
    IDEMPOTENCY_CONFLICT(false),
    VERSION_UNSUPPORTED(false),
    UPSTREAM_MISSING_FIELD(false),
    UPSTREAM_TYPE_MISMATCH(false),
    UPSTREAM_BAD_REQUEST(false),
    UPSTREAM_TIMEOUT(true),
    UPSTREAM_SERVER_ERROR(true),
    UPSTREAM_BAD_GATEWAY(true),
    UPSTREAM_MALFORMED_RESPONSE(false),
    RATE_LIMITED(false),
    UPSTREAM_CIRCUIT_OPEN(false),
    UPSTREAM_DEGRADED(false),
    INTERNAL_ERROR(false);

    private final boolean retryable;

    GatewayErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
