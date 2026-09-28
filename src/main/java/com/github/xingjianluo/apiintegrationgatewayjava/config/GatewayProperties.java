package com.github.xingjianluo.apiintegrationgatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 接入层全部可调参数，统一以 gateway.* 前缀配置。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Upstream upstream = new Upstream();
    private final Retry retry = new Retry();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final RateLimit rateLimit = new RateLimit();

    public Upstream getUpstream() {
        return upstream;
    }

    public Retry getRetry() {
        return retry;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public static class Upstream {
        /** 模拟上游基础地址。 */
        private String baseUrl = "";
        /** 调用上游的读超时。 */
        private Duration readTimeout = Duration.ofMillis(300);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class Retry {
        /** 临时失败最大尝试次数（含首次）。 */
        private int maxAttempts = 3;
        /** 退避初始间隔。 */
        private Duration backoff = Duration.ofMillis(20);
        /** 退避倍数。 */
        private double multiplier = 2.0;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBackoff() {
            return backoff;
        }

        public void setBackoff(Duration backoff) {
            this.backoff = backoff;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }
    }

    public static class CircuitBreaker {
        /** 窗口期内触发熔断的连续失败次数。 */
        private int failureThreshold = 5;
        /** 熔断打开后保持时长，之后进入半开试探。 */
        private Duration openDuration = Duration.ofSeconds(10);

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }
    }

    public static class RateLimit {
        /** 每个客户端在固定窗口内允许的最大请求数。 */
        private int permitsPerWindow = 10;
        /** 固定窗口长度。 */
        private Duration window = Duration.ofMinutes(1);

        public int getPermitsPerWindow() {
            return permitsPerWindow;
        }

        public void setPermitsPerWindow(int permitsPerWindow) {
            this.permitsPerWindow = permitsPerWindow;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }
}
