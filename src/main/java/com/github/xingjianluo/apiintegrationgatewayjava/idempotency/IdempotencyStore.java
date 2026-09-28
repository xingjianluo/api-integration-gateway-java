package com.github.xingjianluo.apiintegrationgatewayjava.idempotency;

import com.github.xingjianluo.apiintegrationgatewayjava.model.GatewayOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 幂等存储（single-flight）。
 *
 * <ul>
 *   <li>同一 (clientId, idempotencyKey) 的首个请求真正执行，结果（成功或确定性业务拒绝）
 *       被永久保留用于回放；</li>
 *   <li>并发重复提交只等待并复用同一个 future，绝无第二次上游调用；</li>
 *   <li>上游临时不可用（抛异常）属于非确定性结果，不固化，移除 pending，
 *       使稍后的同键请求仍可重试。</li>
 * </ul>
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final ConcurrentHashMap<String, CompletableFuture<GatewayOutcome>> store =
            new ConcurrentHashMap<>();

    /**
     * 执行结果 + 是否命中已有结果（顺序重放或并发等待都算 replay）。
     */
    public record ExecutionResult(GatewayOutcome outcome, boolean replay) {
    }

    public ExecutionResult executeOnce(String clientId,
                                       String idempotencyKey,
                                       Supplier<GatewayOutcome> firstExecution) {
        String key = clientId + '|' + idempotencyKey;
        CompletableFuture<GatewayOutcome> created = new CompletableFuture<>();
        CompletableFuture<GatewayOutcome> existing = store.putIfAbsent(key, created);
        if (existing != null) {
            log.debug("命中幂等(并发/重放) client={} key={} done={}",
                    clientId, idempotencyKey, existing.isDone());
            return new ExecutionResult(existing.join(), true);
        }
        try {
            GatewayOutcome outcome = firstExecution.get();
            created.complete(outcome);
            return new ExecutionResult(outcome, false);
        } catch (RuntimeException ex) {
            // 非确定性（上游临时失败）：不要固化失败，移除自己后抛出，允许后续重试。
            store.remove(key, created);
            created.completeExceptionally(ex);
            throw ex;
        }
    }

    public Optional<GatewayOutcome> peek(String clientId, String idempotencyKey) {
        CompletableFuture<GatewayOutcome> f = store.get(clientId + '|' + idempotencyKey);
        if (f != null && f.isDone() && !f.isCompletedExceptionally()) {
            return Optional.ofNullable(f.getNow(null));
        }
        return Optional.empty();
    }

    public void reset() {
        store.clear();
    }
}
