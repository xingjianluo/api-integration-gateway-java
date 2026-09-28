package com.github.xingjianluo.apiintegrationgatewayjava.unit;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.CircuitState;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.DefaultCircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerTest {

    private DefaultCircuitBreaker breaker(int threshold, Duration openFor) {
        GatewayProperties props = new GatewayProperties();
        props.getCircuitBreaker().setFailureThreshold(threshold);
        props.getCircuitBreaker().setOpenDuration(openFor);
        return new DefaultCircuitBreaker(props);
    }

    @Test
    void opens_after_threshold_and_fast_fails_then_allows_probe_after_cooldown() throws Exception {
        DefaultCircuitBreaker cb = breaker(2, Duration.ofMillis(80));
        cb.recordFailure();
        cb.allowRequest(); // 仍 CLOSED
        cb.recordFailure();

        assertThat(cb.currentState()).isEqualTo(CircuitState.OPEN);
        assertThatThrownBy(cb::allowRequest)
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getErrorCode())
                .isEqualTo(GatewayErrorCode.UPSTREAM_CIRCUIT_OPEN);

        Thread.sleep(100);
        cb.allowRequest(); // 冷却后进入 HALF_OPEN 放探测
        assertThat(cb.currentState()).isEqualTo(CircuitState.HALF_OPEN);

        cb.recordSuccess();
        assertThat(cb.currentState()).isEqualTo(CircuitState.CLOSED);
        assertThat(cb.consecutiveFailures()).isZero();
    }

    @Test
    void success_resets_failure_count() {
        DefaultCircuitBreaker cb = breaker(3, Duration.ofSeconds(1));
        cb.recordFailure();
        cb.recordSuccess();
        assertThat(cb.consecutiveFailures()).isZero();
        cb.allowRequest();
        assertThat(cb.currentState()).isEqualTo(CircuitState.CLOSED);
    }
}
