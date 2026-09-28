package com.github.xingjianluo.apiintegrationgatewayjava.web;

import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException e) {
        HttpStatus status = mapStatus(e.getErrorCode());
        ErrorResponse body = new ErrorResponse(
                MDC.get(RequestIdFilter.MDC_KEY),
                e.getErrorCode().name(),
                e.getMessage(),
                e.getErrorCode().isRetryable());
        // 业务可预期错误用 warn；错误详情不外泄原始堆栈
        log.warn("请求被拒绝 requestId={} errorCode={} httpStatus={} retryable={} message={}",
                body.requestId(), body.errorCode(), status.value(), body.retryable(), e.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return gatewayError(HttpStatus.BAD_REQUEST, GatewayErrorCode.UPSTREAM_BAD_REQUEST,
                "请求体不是合法的 JSON");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // 原始异常仅记录在服务端日志，对外返回固定文案
        log.error("请求处理发生未预期异常 requestId={}", MDC.get(RequestIdFilter.MDC_KEY), e);
        return gatewayError(HttpStatus.INTERNAL_SERVER_ERROR, GatewayErrorCode.INTERNAL_ERROR,
                "网关内部错误，请稍后重试");
    }

    private ResponseEntity<ErrorResponse> gatewayError(HttpStatus status,
                                                       GatewayErrorCode code,
                                                       String message) {
        ErrorResponse body = new ErrorResponse(
                MDC.get(RequestIdFilter.MDC_KEY), code.name(), message, code.isRetryable());
        return ResponseEntity.status(status).body(body);
    }

    private HttpStatus mapStatus(GatewayErrorCode code) {
        return switch (code) {
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case VERSION_UNSUPPORTED, UPSTREAM_BAD_REQUEST, UPSTREAM_MISSING_FIELD,
                 UPSTREAM_TYPE_MISMATCH, UPSTREAM_MALFORMED_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case UPSTREAM_CIRCUIT_OPEN, UPSTREAM_DEGRADED -> HttpStatus.SERVICE_UNAVAILABLE;
            case UPSTREAM_TIMEOUT, UPSTREAM_SERVER_ERROR, UPSTREAM_BAD_GATEWAY ->
                    HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
