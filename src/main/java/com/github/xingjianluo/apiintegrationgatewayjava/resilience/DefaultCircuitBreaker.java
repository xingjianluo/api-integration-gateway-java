package com.github.xingjianluo.apiintegrationgatewayjava.resilience;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 简易熔断器：CLOSED 下连续失败达到阈值 -> OPEN，OPEN 期间所有请求被快速拒绝
 * （UPSTREAM_CIRCUIT_OPEN，不打上游）；OPEN 持续 openDuration 后放一个探测请求进入
 * HALF_OPEN，成功则回到 CLOSED，失败则重新 OPEN。保证上游持续故障时不拖垮后续请求。
 */
@Component
public class DefaultCircuitBreaker implements CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(DefaultCircuitBreaker.class);

    private final GatewayProperties properties;

    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures = 0;
    private long openUntilMillis = 0L;

    public DefaultCircuitBreaker(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void allowRequest() {
        long now = System.currentTimeMillis();
        if (state == CircuitState.OPEN) {
            if (now >= openUntilMillis) {
                state = CircuitState.HALF_OPEN;
                log.warn("熔断器进入 HALF_OPEN，允许一个探测请求 failures={}", consecutiveFailures);
            } else {
                throw new GatewayException(GatewayErrorCode.UPSTREAM_CIRCUIT_OPEN,
                        "上游熔断中，连续失败 " + consecutiveFailures + " 次，请稍后重试");
            }
        }
    }

    @Override
    public synchronized void recordSuccess() {
        if (state != CircuitState.CLOSED || consecutiveFailures != 0) {
            log.info("熔断器恢复 CLOSED previousState={}", state);
        }
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        openUntilMillis = 0L;
    }

    @Override
    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == CircuitState.HALF_OPEN || consecutiveFailures >= threshold()) {
            state = CircuitState.OPEN;
            openUntilMillis = System.currentTimeMillis() + properties.getCircuitBreaker().getOpenDuration().toMillis();
            log.warn("熔断器打开 OPEN consecutiveFailures={} openFor={}ms",
                    consecutiveFailures, properties.getCircuitBreaker().getOpenDuration().toMillis());
        }
    }

    public synchronized CircuitState currentState() {
        return state;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized void reset() {
        state = CircuitState.CLOSED;
        consecutiveFailures = 0;
        openUntilMillis = 0L;
    }

    private int threshold() {
        return Math.max(1, properties.getCircuitBreaker().getFailureThreshold());
    }
}
