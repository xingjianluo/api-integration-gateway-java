package com.github.xingjianluo.apiintegrationgatewayjava.ratelimit;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按客户端标识的固定窗口限流器。
 *
 * <p>每个客户端在任意长度为 gateway.rate-limit.window 的窗口内最多允许
 * gateway.rate-limit.permits-per-window 次请求（含幂等重放）；超出直接拒绝，
 * 不进入幂等处理、也不打上游。窗口随该客户端首次请求滚动。
 */
@Component
public class FixedWindowRateLimiter implements RateLimiter {

    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean tryAcquire(String clientKey) {
        Window window = windows.computeIfAbsent(clientKey, k -> new Window());
        long now = System.currentTimeMillis();
        synchronized (window) {
            long windowMillis = properties.getRateLimit().getWindow().toMillis();
            if (now - window.startMillis >= windowMillis) {
                window.startMillis = now;
                window.count = 0;
            }
            if (window.count >= properties.getRateLimit().getPermitsPerWindow()) {
                return false;
            }
            window.count++;
            return true;
        }
    }

    /** 测试/管理用：重置某客户端的窗口。 */
    public void reset(String clientKey) {
        windows.remove(clientKey);
    }

    /** 测试/管理用：重置全部窗口。 */
    public void clear() {
        windows.clear();
    }

    private static final class Window {
        long startMillis = System.currentTimeMillis();
        int count;
    }
}
