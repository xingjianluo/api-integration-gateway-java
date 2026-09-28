package com.github.xingjianluo.apiintegrationgatewayjava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 对外 HTTP 响应体：成功、拒绝、回放都使用同一结构。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GatewayResponse(
        boolean success,
        boolean replay,
        boolean degraded,
        String requestId,
        String apiVersion,
        String rejectReason,
        String message,
        UnifiedOrder order
) {
}
