package com.github.xingjianluo.apiintegrationgatewayjava.gateway.web;

import com.github.xingjianluo.apiintegrationgatewayjava.model.ErrorResponse;
import com.github.xingjianluo.apiintegrationgatewayjava.model.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.model.RejectReason;
import com.github.xingjianluo.apiintegrationgatewayjava.model.UpstreamErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.EnumMap;
import java.util.Map;

/**
 * 把归一后的 {@link GatewayException} 映射为稳定、可区分的对外错误响应；
 * 任何未预期异常也被收敛为通用 500，绝不透出内部堆栈或上游原始异常。
 */
@RestControllerAdvice(basePackages = "com.github.xingjianluo.apiintegrationgatewayjava.gateway.web")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<RejectReason, HttpStatus> STATUS = new EnumMap<>(RejectReason.class);

    static {
        STATUS.put(RejectReason.VERSION_UNSUPPORTED, HttpStatus.BAD_REQUEST);
        STATUS.put(RejectReason.MISSING_FIELD, HttpStatus.BAD_GATEWAY);
        STATUS.put(RejectReason.FIELD_TYPE_MISMATCH, HttpStatus.BAD_GATEWAY);
        STATUS.put(RejectReason.UPSTREAM_ORDER_NOT_FOUND, HttpStatus.NOT_FOUND);
        STATUS.put(RejectReason.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS);
        STATUS.put(RejectReason.UPSTREAM_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handle(GatewayException ex) {
        HttpStatus status = STATUS.getOrDefault(ex.getRejectReason(), HttpStatus.INTERNAL_SERVER_ERROR);
        String code = resolveCode(ex);
        // 不记录/输出 cause 堆栈到响应体。
        log.info("对外拒绝 requestId={} httpStatus={} code={} reason={}",
                ex.getRequestId(), status.value(), code, ex.getRejectReason());
        return ResponseEntity.status(status).body(new ErrorResponse(
                false, ex.getRequestId(), code, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        log.error("未预期异常被收敛（不对外透传）", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(false, null, "INTERNAL_ERROR", "internal error"));
    }

    private String resolveCode(GatewayException ex) {
        if (ex.getRejectReason() != null) {
            return ex.getRejectReason().name();
        }
        UpstreamErrorCode c = ex.getUpstreamErrorCode();
        return c != null ? c.name() : "INTERNAL_ERROR";
    }
}
