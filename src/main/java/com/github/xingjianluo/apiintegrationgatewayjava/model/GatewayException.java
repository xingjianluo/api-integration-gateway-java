package com.github.xingjianluo.apiintegrationgatewayjava.model;

/**
 * 网关对外唯一可见的异常类型，携带归一后的错误码与拒绝原因；
 * 上游原始异常绝不直接透出。
 */
public class GatewayException extends RuntimeException {

    private final UpstreamErrorCode upstreamErrorCode;
    private final RejectReason rejectReason;
    private final String requestId;

    public GatewayException(UpstreamErrorCode upstreamErrorCode,
                            RejectReason rejectReason,
                            String requestId,
                            String message,
                            Throwable cause) {
        super(message, cause);
        this.upstreamErrorCode = upstreamErrorCode;
        this.rejectReason = rejectReason;
        this.requestId = requestId;
    }

    public UpstreamErrorCode getUpstreamErrorCode() {
        return upstreamErrorCode;
    }

    public RejectReason getRejectReason() {
        return rejectReason;
    }

    public String getRequestId() {
        return requestId;
    }
}
