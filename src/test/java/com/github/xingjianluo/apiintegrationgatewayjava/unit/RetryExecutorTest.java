package com.github.xingjianluo.apiintegrationgatewayjava.unit;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.BackoffRetryExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryExecutorTest {

    private BackoffRetryExecutor executor(int maxAttempts) {
        GatewayProperties props = new GatewayProperties();
        props.getRetry().setMaxAttempts(maxAttempts);
        props.getRetry().setBackoff(Duration.ofMillis(1));
        props.getRetry().setMaxBackoff(Duration.ofMillis(2));
        return new BackoffRetryExecutor(props) {
            @Override
            protected void sleep(long millis) {
                // 单元测试不真正睡眠
            }
        };
    }

    @Test
    void retries_retryable_error_until_success() {
        BackoffRetryExecutor exec = executor(3);
        AtomicInteger calls = new AtomicInteger();
        String result = exec.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new GatewayException(GatewayErrorCode.UPSTREAM_TIMEOUT, "timeout");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void does_not_retry_non_retryable_error() {
        BackoffRetryExecutor exec = executor(3);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> exec.execute(() -> {
            calls.incrementAndGet();
            throw new GatewayException(GatewayErrorCode.UPSTREAM_MISSING_FIELD, "missing");
        })).extracting(e -> ((GatewayException) e).getErrorCode())
                .isEqualTo(GatewayErrorCode.UPSTREAM_MISSING_FIELD);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void exhausts_attempts_and_throws_last_error() {
        BackoffRetryExecutor exec = executor(2);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> exec.execute(() -> {
            calls.incrementAndGet();
            throw new GatewayException(GatewayErrorCode.UPSTREAM_SERVER_ERROR, "500");
        })).extracting(e -> ((GatewayException) e).getErrorCode())
                .isEqualTo(GatewayErrorCode.UPSTREAM_SERVER_ERROR);
        assertThat(calls.get()).isEqualTo(2);
    }
}
