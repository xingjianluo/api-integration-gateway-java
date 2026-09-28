package com.github.xingjianluo.apiintegrationgatewayjava.upstream.mock;

import com.github.xingjianluo.apiintegrationgatewayjava.model.ApiVersion;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记录模拟上游各版本被真正调用的次数，供测试断言“只打到上游一次”。
 */
@Component
public class UpstreamStats {

    private final Map<ApiVersion, AtomicInteger> perVersion = new EnumMap<>(ApiVersion.class);
    private final AtomicInteger total = new AtomicInteger(0);

    public UpstreamStats() {
        for (ApiVersion v : ApiVersion.values()) {
            perVersion.put(v, new AtomicInteger(0));
        }
    }

    public void recordInvocation(ApiVersion version) {
        total.incrementAndGet();
        perVersion.get(version).incrementAndGet();
    }

    public int getTotal() {
        return total.get();
    }

    public int get(ApiVersion version) {
        return perVersion.get(version).get();
    }

    public void reset() {
        total.set(0);
        perVersion.values().forEach(c -> c.set(0));
    }
}
