package com.github.xingjianluo.apiintegrationgatewayjava.ratelimit;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按客户端的固定窗口限流。
 * 窗口内超过 {@code gateway.rate-limit.permits-per-window} 的请求直接拒绝（RATE_LIMITED）。
 * 计数发生在幂等判定之前，因此即使是重放也占用配额（可在文档中说明）。
 */
@Component
public class ClientRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ClientRateLimiter.class);

    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public ClientRateLimiter(GatewayProperties properties) {
        this.properties = properties;
    }

    public boolean tryAcquire(String clientId) {
        long now = System.currentTimeMillis();
        long windowMs = properties.getRateLimit().getWindow().toMillis();
        int permits = properties.getRateLimit().getPermitsPerWindow();

        Window window = windows.computeIfAbsent(clientId, k -> new Window(now));
        synchronized (window) {
            if (now - window.startEpochMilli >= windowMs) {
                // 窗口滚动
                window.startEpochMilli = now;
                window.count.set(0);
            }
            int used = window.count.incrementAndGet();
            boolean allowed = used <= permits;
            if (!allowed) {
                log.warn("客户端限流拒绝 client={} used={} permits={} windowMs={}",
                        clientId, used, permits, windowMs);
            }
            return allowed;
        }
    }

    public void reset() {
        windows.clear();
    }

    private static final class Window {
        private long startEpochMilli;
        private final AtomicInteger count = new AtomicInteger(0);

        private Window(long startEpochMilli) {
            this.startEpochMilli = startEpochMilli;
        }
    }
}
