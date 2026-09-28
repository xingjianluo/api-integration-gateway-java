package com.github.xingjianluo.apiintegrationgatewayjava.resilience;

import com.github.xingjianluo.apiintegrationgatewayjava.model.UnifiedOrder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按订单号保存最近一次成功结果，用于上游连续失败/熔断时的降级返回。
 */
@Component
public class LastGoodCache {

    private final ConcurrentHashMap<String, UnifiedOrder> cache = new ConcurrentHashMap<>();

    public void put(String orderId, UnifiedOrder order) {
        cache.put(orderId, order);
    }

    public Optional<UnifiedOrder> get(String orderId) {
        return Optional.ofNullable(cache.get(orderId));
    }

    public void reset() {
        cache.clear();
    }
}
