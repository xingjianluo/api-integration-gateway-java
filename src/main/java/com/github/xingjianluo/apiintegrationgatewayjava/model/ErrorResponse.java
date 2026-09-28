package com.github.xingjianluo.apiintegrationgatewayjava.model;

/**
 * 系统级异常（限流、上游不可用等）的统一错误响应体。
 */
public record ErrorResponse(
        boolean success,
        String requestId,
        String errorCode,
        String message
) {
}
