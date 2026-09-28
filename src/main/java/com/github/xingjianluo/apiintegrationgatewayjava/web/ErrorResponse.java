package com.github.xingjianluo.apiintegrationgatewayjava.web;

public record ErrorResponse(
        String requestId,
        String errorCode,
        String message,
        boolean retryable
) {
}
