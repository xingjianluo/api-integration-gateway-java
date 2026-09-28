package com.github.xingjianluo.apiintegrationgatewayjava.model;

/**
 * 归一后的上游错误类型；不向上游外抛出原始异常。
 */
public enum UpstreamErrorCode {
    /** 上游调用超时（临时，可重试）。 */
    UPSTREAM_TIMEOUT(true),
    /** 上游返回非成功状态码，且归类为临时（如 5xx，可重试）。 */
    UPSTREAM_BAD_STATUS_TRANSIENT(true),
    /** 上游内部异常/连接异常（临时，可重试）。 */
    UPSTREAM_INTERNAL_ERROR(true),
    /** 上游 404：订单不存在（不重试）。 */
    UPSTREAM_NOT_FOUND(false);

    private final boolean retryable;

    UpstreamErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
