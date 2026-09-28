package com.github.xingjianluo.apiintegrationgatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Upstream upstream = new Upstream();
    private final Idempotency idempotency = new Idempotency();
    private final RateLimit rateLimit = new RateLimit();
    private final Retry retry = new Retry();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Version version = new Version();
    private final Mock mock = new Mock();

    public Upstream getUpstream() { return upstream; }
    public Idempotency getIdempotency() { return idempotency; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Retry getRetry() { return retry; }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public Version getVersion() { return version; }
    public Mock getMock() { return mock; }

    public static class Upstream {
        private String baseUrl = "http://localhost:${server.port}/mock-upstream";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(2);
        private String paymentsPath = "/payments";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public String getPaymentsPath() { return paymentsPath; }
        public void setPaymentsPath(String paymentsPath) { this.paymentsPath = paymentsPath; }
    }

    public static class Idempotency {
        private Duration ttl = Duration.ofMinutes(10);
        private Duration acquireTimeout = Duration.ofSeconds(5);

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public Duration getAcquireTimeout() { return acquireTimeout; }
        public void setAcquireTimeout(Duration acquireTimeout) { this.acquireTimeout = acquireTimeout; }
    }

    public static class RateLimit {
        private int permitsPerWindow = 10;
        private Duration window = Duration.ofMinutes(1);

        public int getPermitsPerWindow() { return permitsPerWindow; }
        public void setPermitsPerWindow(int permitsPerWindow) { this.permitsPerWindow = permitsPerWindow; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private Duration backoff = Duration.ofMillis(100);
        private double multiplier = 2.0;
        private Duration maxBackoff = Duration.ofSeconds(1);

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getBackoff() { return backoff; }
        public void setBackoff(Duration backoff) { this.backoff = backoff; }
        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
        public Duration getMaxBackoff() { return maxBackoff; }
        public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
    }

    public static class CircuitBreaker {
        private int failureThreshold = 5;
        private Duration openDuration = Duration.ofSeconds(10);

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public Duration getOpenDuration() { return openDuration; }
        public void setOpenDuration(Duration openDuration) { this.openDuration = openDuration; }
    }

    public static class Version {
        private String defaultVersion = "v1";
        private List<String> supported = new ArrayList<>(List.of("v1", "v2", "v3"));

        public String getDefaultVersion() { return defaultVersion; }
        public void setDefaultVersion(String defaultVersion) { this.defaultVersion = defaultVersion; }
        public List<String> getSupported() { return supported; }
        public void setSupported(List<String> supported) { this.supported = supported; }
    }

    public static class Mock {
        private List<String> supportedUpstreamVersions = new ArrayList<>(List.of("v1", "v2", "v3"));
        private Duration v2Latency = Duration.ofMillis(50);

        public List<String> getSupportedUpstreamVersions() { return supportedUpstreamVersions; }
        public void setSupportedUpstreamVersions(List<String> supportedUpstreamVersions) {
            this.supportedUpstreamVersions = supportedUpstreamVersions;
        }
        public Duration getV2Latency() { return v2Latency; }
        public void setV2Latency(Duration v2Latency) { this.v2Latency = v2Latency; }
    }
}
