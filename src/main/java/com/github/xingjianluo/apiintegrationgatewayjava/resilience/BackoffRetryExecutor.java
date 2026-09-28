package com.github.xingjianluo.apiintegrationgatewayjava.resilience;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 对临时性失败（{@link com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode#isRetryable()}）
 * 做指数退避重试；非临时性错误（4xx、字段缺失、类型不匹配等）立即抛出，不做无意义重试。
 *
 * <p>退避序列：backoff * multiplier^(attempt-1)，上限 maxBackoff。达到最大次数仍失败时，
 * 抛出最后一次的异常（由上层熔断器记为一次失败）。
 */
@Component
public class BackoffRetryExecutor implements RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(BackoffRetryExecutor.class);

    private final GatewayProperties properties;

    public BackoffRetryExecutor(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        GatewayException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (GatewayException e) {
                last = e;
                if (!e.getErrorCode().isRetryable() || attempt == maxAttempts) {
                    throw e;
                }
                long delay = backoffMillis(attempt);
                log.warn("上游临时失败，准备退避重试 requestId={} attempt={}/{} errorCode={} backoffMs={}",
                        MDC.get("requestId"), attempt, maxAttempts, e.getErrorCode(), delay);
                sleep(delay);
            } catch (RuntimeException e) {
                throw new GatewayException(
                        com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode.INTERNAL_ERROR,
                        "上游调用过程中发生内部错误", e);
            }
        }
        throw last;
    }

    private long backoffMillis(int attempt) {
        double factor = Math.pow(properties.getRetry().getMultiplier(), attempt - 1);
        long computed = (long) (properties.getRetry().getBackoff().toMillis() * factor);
        return Math.min(computed, properties.getRetry().getMaxBackoff().toMillis());
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(
                    com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode.INTERNAL_ERROR,
                    "重试等待被中断", e);
        }
    }
}
