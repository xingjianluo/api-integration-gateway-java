package com.github.xingjianluo.apiintegrationgatewayjava.idempotency;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 进程内幂等存储。
 *
 * <p>语义：
 * <ul>
 *   <li>同一幂等键在 TTL 内只有一个线程（leader）真正执行 action，上游只被打到一次；</li>
 *   <li>并发重复请求（follower）在条件上限时等待，最终拿到与首次完全一致的成功或失败结果，
 *       结果中的 replay=true；</li>
 *   <li>同一幂等键但请求体摘要不同 -> 立即抛 IDEMPOTENCY_CONFLICT（409）；</li>
 *   <li>等待超过 gateway.idempotency.acquire-timeout 仍未完成 -> 明确拒绝（INTERNAL_ERROR），
 *       不会自己再去打一次上游；</li>
 *   <li>成功与失败结果都会缓存到 TTL，保证失败重放也一致。</li>
 * </ul>
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, Entry<?>> store = new ConcurrentHashMap<>();

    public InMemoryIdempotencyStore(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public <T> IdempotentResult<T> execute(String key, String requestHash, Supplier<T> action) {
        String rid = MDC.get("requestId");
        @SuppressWarnings("unchecked")
        Entry<T> entry = (Entry<T>) store.computeIfAbsent(key, k -> new Entry<>());

        boolean locked;
        try {
            locked = entry.lock.tryLock(acquireTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR, "等待幂等锁时被中断", e);
        }
        if (!locked) {
            log.warn("等待同幂等键首请求超时 requestId={} idempotencyKey={} reason=lock-acquire-timeout", rid, key);
            throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                    "幂等首请求仍在处理中，等待超时，请稍后用相同幂等键重试");
        }

        try {
            long now = System.currentTimeMillis();
            if (entry.done && entry.expired(now, ttlMillis())) {
                entry.resetForNewLeader(requestHash);
            } else if (entry.hashSet && !entry.hash.equals(requestHash)) {
                log.warn("幂等键冲突 requestId={} idempotencyKey={} reason=different-request-body", rid, key);
                throw new GatewayException(GatewayErrorCode.IDEMPOTENCY_CONFLICT,
                        "幂等键 " + key + " 已用于不同的请求内容");
            }

            if (entry.done) {
                log.info("幂等命中直接返回缓存结果 requestId={} idempotencyKey={} replay=true", rid, key);
                return replay(entry);
            }

            if (!entry.running) {
                // leader：占下执行权后释放锁去调用上游，避免阻塞 follower 的限时等待
                entry.running = true;
                entry.hashSet = true;
                entry.hash = requestHash;
                entry.lock.unlock();

                T value;
                try {
                    value = action.get();
                } catch (GatewayException e) {
                    entry.lock.lock();
                    try {
                        entry.completeWithError(e);
                    } finally {
                        entry.lock.unlock();
                    }
                    log.info("首次请求失败已缓存 requestId={} idempotencyKey={} errorCode={}",
                            rid, key, e.getErrorCode());
                    return IdempotentResult.failure(e, false);
                } catch (RuntimeException e) {
                    entry.lock.lock();
                    try {
                        entry.abort();
                    } finally {
                        entry.lock.unlock();
                    }
                    log.error("首次请求发生未预期异常，释放幂等占位 requestId={} idempotencyKey={}", rid, key, e);
                    throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                            "处理请求时发生内部错误", e);
                }
                entry.lock.lock();
                try {
                    entry.completeWithValue(value);
                } finally {
                    entry.lock.unlock();
                }
                log.info("首次请求成功已缓存 requestId={} idempotencyKey={}", rid, key);
                return IdempotentResult.success(value, false);
            }

            // follower：在锁内限时等待 leader 完成
            long deadline = System.currentTimeMillis() + acquireTimeoutMillis();
            while (entry.running && !entry.done) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.warn("等待同幂等键首请求超时 requestId={} idempotencyKey={} reason=acquire-timeout", rid, key);
                    throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                            "幂等首请求仍在处理中，等待超时，请稍后用相同幂等键重试");
                }
                try {
                    boolean signaled = entry.doneCondition.await(remaining, TimeUnit.MILLISECONDS);
                    if (!signaled && entry.running && !entry.done &&
                            System.currentTimeMillis() >= deadline) {
                        log.warn("等待同幂等键首请求超时 requestId={} idempotencyKey={} reason=acquire-timeout", rid, key);
                        throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                                "幂等首请求仍在处理中，等待超时，请稍后用相同幂等键重试");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                            "等待幂等首请求时被中断", ie);
                }
            }
            log.info("并发重复请求返回首次一致结果 requestId={} idempotencyKey={} replay=true", rid, key);
            return replay(entry);
        } finally {
            if (entry.lock.isHeldByCurrentThread()) {
                entry.lock.unlock();
            }
        }
    }

    @Scheduled(fixedDelayString = "${gateway.idempotency.sweep-delay-ms:60000}")
    public void sweepExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry<?>>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry<?>> mapEntry = it.next();
            Entry<?> entry = mapEntry.getValue();
            if (!entry.lock.tryLock()) {
                continue;
            }
            try {
                if (!entry.running && entry.done && entry.expired(now, ttlMillis())) {
                    it.remove();
                }
            } finally {
                entry.lock.unlock();
            }
        }
    }

    /** 测试/管理用：清空全部幂等记录。 */
    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    private static <T> IdempotentResult<T> replay(Entry<T> entry) {
        if (entry.error != null) {
            return IdempotentResult.failure(entry.error, true);
        }
        return IdempotentResult.success(entry.value, true);
    }

    private long ttlMillis() {
        return properties.getIdempotency().getTtl().toMillis();
    }

    private long acquireTimeoutMillis() {
        return properties.getIdempotency().getAcquireTimeout().toMillis();
    }

    private static final class Entry<T> {
        final ReentrantLock lock = new ReentrantLock();
        final Condition doneCondition = lock.newCondition();

        boolean running;
        boolean done;
        boolean hashSet;
        T value;
        GatewayException error;
        String hash = "";
        long completedAt;

        boolean expired(long now, long ttlMillis) {
            return now - completedAt > ttlMillis;
        }

        void completeWithValue(T value) {
            this.value = value;
            this.error = null;
            this.done = true;
            this.running = false;
            this.completedAt = System.currentTimeMillis();
            doneCondition.signalAll();
        }

        void completeWithError(GatewayException error) {
            this.value = null;
            this.error = error;
            this.done = true;
            this.running = false;
            this.completedAt = System.currentTimeMillis();
            doneCondition.signalAll();
        }

        void abort() {
            this.running = false;
            this.done = false;
            this.value = null;
            this.error = null;
            this.hash = "";
            this.hashSet = false;
            this.completedAt = 0L;
            doneCondition.signalAll();
        }

        void resetForNewLeader(String requestHash) {
            this.running = false;
            this.done = false;
            this.value = null;
            this.error = null;
            this.hash = requestHash;
            this.hashSet = true;
            this.completedAt = 0L;
        }
    }
}
