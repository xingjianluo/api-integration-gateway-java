package com.github.xingjianluo.apiintegrationgatewayjava.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.idempotency.IdempotencyStore;
import com.github.xingjianluo.apiintegrationgatewayjava.model.ApiVersion;
import com.github.xingjianluo.apiintegrationgatewayjava.model.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.model.GatewayOutcome;
import com.github.xingjianluo.apiintegrationgatewayjava.model.GatewayResponse;
import com.github.xingjianluo.apiintegrationgatewayjava.model.RejectReason;
import com.github.xingjianluo.apiintegrationgatewayjava.model.UnifiedOrder;
import com.github.xingjianluo.apiintegrationgatewayjava.model.UpstreamErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.normalizer.ResponseNormalizer;
import com.github.xingjianluo.apiintegrationgatewayjava.ratelimit.ClientRateLimiter;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.CircuitBreaker;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.LastGoodCache;
import com.github.xingjianluo.apiintegrationgatewayjava.upstream.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 接入层核心编排。
 * 顺序：限流 -> 版本判定 -> 幂等单飞 ->（熔断 -> 退避重试调用 -> 归一）-> 降级。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
    private static final ApiVersion DEFAULT_VERSION = ApiVersion.V3;

    private final UpstreamClient upstreamClient;
    private final ResponseNormalizer normalizer;
    private final IdempotencyStore idempotencyStore;
    private final ClientRateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final LastGoodCache lastGoodCache;
    private final GatewayProperties properties;

    public GatewayService(UpstreamClient upstreamClient,
                          ResponseNormalizer normalizer,
                          IdempotencyStore idempotencyStore,
                          ClientRateLimiter rateLimiter,
                          CircuitBreaker circuitBreaker,
                          LastGoodCache lastGoodCache,
                          GatewayProperties properties) {
        this.upstreamClient = upstreamClient;
        this.normalizer = normalizer;
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.lastGoodCache = lastGoodCache;
        this.properties = properties;
    }

    public GatewayResponse queryOrder(String clientId,
                                      String idempotencyKey,
                                      String versionHeader,
                                      String orderId,
                                      String mockMode) {
        String requestId = newRequestId();

        // 1. 客户端限流（计数先于幂等）。
        if (!rateLimiter.tryAcquire(clientId)) {
            log.warn("requestId={} client={} orderId={} decision=REJECT reason=RATE_LIMITED basis=quota-exceeded",
                    requestId, clientId, orderId);
            throw new GatewayException(null, RejectReason.RATE_LIMITED, requestId,
                    "客户端调用超过配额", null);
        }

        // 2. 版本判定：显式头优先，缺省走默认版本；无法识别则确定性拒绝。
        ApiVersion version;
        String basis;
        if (versionHeader == null || versionHeader.isBlank()) {
            version = DEFAULT_VERSION;
            basis = "header-absent->default:" + version;
        } else {
            version = ApiVersion.fromHeader(versionHeader);
            basis = "header:" + versionHeader;
            if (version == null) {
                log.warn("requestId={} client={} orderId={} versionHeader={} decision=REJECT "
                                + "reason=VERSION_UNSUPPORTED basis={}",
                        requestId, clientId, orderId, versionHeader, basis);
                throw new GatewayException(null, RejectReason.VERSION_UNSUPPORTED, requestId,
                        "不支持的上游版本: " + versionHeader, null);
            }
        }

        // 3. 无幂等键：直接执行（结果不回放）。
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            GatewayOutcome outcome = doDeterministic(requestId, version, basis, orderId, mockMode);
            return toResponse(outcome, false, requestId);
        }

        // 4. 幂等单飞：并发重复提交只真正调用上游一次。
        IdempotencyStore.ExecutionResult result = idempotencyStore.executeOnce(
                clientId, idempotencyKey,
                () -> doDeterministic(requestId, version, basis, orderId, mockMode));
        GatewayOutcome outcome = result.outcome();
        boolean replay = result.replay();
        // 重放时沿用首次固化的 requestId，使日志与响应可关联到同一笔请求。
        String effectiveRequestId = outcome.requestId() != null ? outcome.requestId() : requestId;
        log.info("requestId={} client={} orderId={} idempotencyKey={} version={} decision={} replay={} basis={}",
                effectiveRequestId, clientId, orderId, idempotencyKey,
                outcome.version(), replay ? "REPLAY" : "FIRST", replay,
                replay ? "idempotency-hit" : basis);
        return toResponse(outcome, replay, effectiveRequestId);
    }

    /**
     * 产出确定性结果：成功或确定性业务拒绝都会被幂等层固化；
     * 仅上游临时不可用（无降级）会抛出、不固化。
     */
    private GatewayOutcome doDeterministic(String requestId, ApiVersion version, String basis,
                                           String orderId, String mockMode) {
        // 熔断打开：不打上游，直接尝试降级。
        if (!circuitBreaker.allowRequest()) {
            GatewayOutcome fallback = degradedOrThrow(requestId, version, orderId,
                    "circuit-open");
            if (fallback != null) {
                return fallback;
            }
            throw new GatewayException(UpstreamErrorCode.UPSTREAM_INTERNAL_ERROR,
                    RejectReason.UPSTREAM_UNAVAILABLE, requestId,
                    "上游连续失败且无降级数据", null);
        }

        GatewayException last = null;
        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JsonNode body = upstreamClient.fetchOrder(orderId, version, requestId, mockMode);
                UnifiedOrder order = normalizer.normalize(body, version, requestId);
                circuitBreaker.recordSuccess();
                lastGoodCache.put(orderId, order);
                log.info("requestId={} orderId={} version={} attempt={} decision=FIRST basis={} result=SUCCESS",
                        requestId, orderId, version, attempt, basis);
                return new GatewayOutcome.Success(order, version, false, requestId);
            } catch (GatewayException e) {
                // 归一化类（缺字段/类型不匹配）与 404 是确定性拒绝：不重试、不熔断、立即固化。
                if (e.getRejectReason() != null
                        && e.getRejectReason() != RejectReason.UPSTREAM_UNAVAILABLE) {
                    log.warn("requestId={} orderId={} version={} attempt={} decision=REJECT reason={} basis={}",
                            requestId, orderId, version, attempt, e.getRejectReason(), basis);
                    return new GatewayOutcome.Rejection(
                            e.getRejectReason(), e.getMessage(), version, requestId);
                }
                // 临时失败。
                last = e;
                circuitBreaker.recordFailure();
                log.warn("requestId={} orderId={} version={} attempt={} transientError={} code={}",
                        requestId, orderId, version, attempt, e.getMessage(),
                        e.getUpstreamErrorCode());
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            }
        }

        // 重试耗尽：熔断未打开时仍按失败抛出，以便累计连续失败、并尽快让调用方感知。
        // 不在这里消费降级缓存（降级只在熔断打开期间提供，避免掩盖持续故障）。
        throw new GatewayException(
                last != null ? last.getUpstreamErrorCode() : UpstreamErrorCode.UPSTREAM_INTERNAL_ERROR,
                RejectReason.UPSTREAM_UNAVAILABLE, requestId,
                "上游临时不可用（重试耗尽）", last);
    }

    /**
     * 降级：有最近成功数据则返回（degraded=true），否则返回 null 由调用方拒绝。
     */
    private GatewayOutcome degradedOrThrow(String requestId, ApiVersion version,
                                           String orderId, String reason) {
        return lastGoodCache.get(orderId).map(cached -> {
            log.warn("requestId={} orderId={} version={} decision=DEGRADED basis={} result=CACHED_ORDER",
                    requestId, orderId, version, reason);
            return (GatewayOutcome) new GatewayOutcome.Success(cached, version, true, requestId);
        }).orElse(null);
    }

    private void sleepBackoff(int attempt) {
        long base = properties.getRetry().getBackoff().toMillis();
        double multiplier = properties.getRetry().getMultiplier();
        long delay = (long) (base * Math.pow(multiplier, attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private GatewayResponse toResponse(GatewayOutcome outcome, boolean replay, String currentRequestId) {
        String requestId = outcome.requestId() != null ? outcome.requestId() : currentRequestId;
        if (outcome instanceof GatewayOutcome.Success s) {
            return new GatewayResponse(true, replay, s.degraded(), requestId,
                    s.version().name(), null, null, s.order());
        }
        GatewayOutcome.Rejection r = (GatewayOutcome.Rejection) outcome;
        return new GatewayResponse(false, replay, false, requestId,
                r.version().name(), r.reason().name(), r.message(), null);
    }

    private String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
