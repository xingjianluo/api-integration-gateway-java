package com.github.xingjianluo.apiintegrationgatewayjava.model;

/**
 * 一次网关处理的确定性结果，同时用于幂等回放：成功或业务拒绝都会被原样保存。
 */
public sealed interface GatewayOutcome {

    String requestId();

    ApiVersion version();

    /** 首次成功或确定性拒绝。 */
    record Success(UnifiedOrder order,
                   ApiVersion version,
                   boolean degraded,
                   String requestId) implements GatewayOutcome {
        @Override
        public String requestId() {
            return requestId;
        }

        @Override
        public ApiVersion version() {
            return version;
        }
    }

    /** 归一失败、限流以外的确定性业务拒绝（限流结果不进入幂等缓存）。 */
    record Rejection(RejectReason reason,
                     String message,
                     ApiVersion version,
                     String requestId) implements GatewayOutcome {
        @Override
        public String requestId() {
            return requestId;
        }

        @Override
        public ApiVersion version() {
            return version;
        }
    }
}
