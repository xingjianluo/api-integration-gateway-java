package com.github.xingjianluo.apiintegrationgatewayjava.model;

/**
 * 对外可区分的业务拒绝/失败原因。
 */
public enum RejectReason {
    /** 请求声明（或默认选中）的版本不在兼容范围内。 */
    VERSION_UNSUPPORTED,
    /** 上游返回缺少归一所需的必填字段。 */
    MISSING_FIELD,
    /** 上游字段存在但类型/取值无法转换为对外模型。 */
    FIELD_TYPE_MISMATCH,
    /** 上游明确表示订单不存在（4xx 非临时错误）。 */
    UPSTREAM_ORDER_NOT_FOUND,
    /** 客户端超过调用配额被限流。 */
    RATE_LIMITED,
    /** 上游超时/连续失败且无降级数据可用。 */
    UPSTREAM_UNAVAILABLE
}
