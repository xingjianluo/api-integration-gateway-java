package com.github.xingjianluo.apiintegrationgatewayjava.resilience;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简化熔断器（关闭 / 打开 / 半开）。
 * <ul>
 *   <li>关闭态：连续失败达到阈值后打开；</li>
 *   <li>打开态：{@link #allowRequest()} 直接 false，快速失败并触发降级，不打上游；</li>
 *   <li>打开时长过后进入半开，仅放行一个试探请求，成功则关闭，失败则重新计时打开。</li>
 * </ul>
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final GatewayProperties properties;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAtEpochMilli = new AtomicLong(0);

    public CircuitBreaker(GatewayProperties properties) {
        this.properties = properties;
    }

    public boolean allowRequest() {
        long openedAt = openedAtEpochMilli.get();
        if (openedAt == 0L) {
            return true; // 关闭
        }
        long openMs = properties.getCircuitBreaker().getOpenDuration().toMillis();
        if (System.currentTimeMillis() - openedAt < openMs) {
            return false; // 打开：快速失败
        }
        // 半开：允许一个试探请求通过（打开时间戳保持，试探结果决定走向）。
        log.info("熔断器进入半开试探 openedAt={}", openedAt);
        return true;
    }

    public void recordSuccess() {
        if (openedAtEpochMilli.get() != 0L) {
            log.info("熔断器试探成功，关闭熔断");
        }
        openedAtEpochMilli.set(0);
        consecutiveFailures.set(0);
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        int threshold = properties.getCircuitBreaker().getFailureThreshold();
        if (failures >= threshold || openedAtEpochMilli.get() != 0L) {
            // 达到阈值，或半开试探失败：进入/重新进入打开态。
            openedAtEpochMilli.set(System.currentTimeMillis());
            log.warn("熔断器打开 consecutiveFailures={} threshold={}", failures, threshold);
        }
    }

    public void reset() {
        consecutiveFailures.set(0);
        openedAtEpochMilli.set(0);
    }

    public boolean isOpen() {
        return !allowRequest();
    }
}
