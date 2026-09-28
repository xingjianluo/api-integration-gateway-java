package com.github.xingjianluo.apiintegrationgatewayjava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.idempotency.IdempotentResult;
import com.github.xingjianluo.apiintegrationgatewayjava.idempotency.IdempotencyStore;
import com.github.xingjianluo.apiintegrationgatewayjava.model.NormalizedPayment;
import com.github.xingjianluo.apiintegrationgatewayjava.ratelimit.RateLimiter;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.CircuitBreaker;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.RetryExecutor;
import com.github.xingjianluo.apiintegrationgatewayjava.upstream.UpstreamPaymentClient;
import com.github.xingjianluo.apiintegrationgatewayjava.version.ApiVersion;
import com.github.xingjianluo.apiintegrationgatewayjava.version.PaymentNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DefaultPaymentGatewayService implements PaymentGatewayService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPaymentGatewayService.class);

    private final UpstreamPaymentClient upstreamClient;
    private final PaymentNormalizer normalizer;
    private final RetryExecutor retryExecutor;
    private final CircuitBreaker circuitBreaker;
    private final IdempotencyStore idempotencyStore;
    private final RateLimiter rateLimiter;
    private final GatewayProperties properties;

    public DefaultPaymentGatewayService(UpstreamPaymentClient upstreamClient,
                                        PaymentNormalizer normalizer,
                                        RetryExecutor retryExecutor,
                                        CircuitBreaker circuitBreaker,
                                        IdempotencyStore idempotencyStore,
                                        RateLimiter rateLimiter,
                                        GatewayProperties properties) {
        this.upstreamClient = upstreamClient;
        this.normalizer = normalizer;
        this.retryExecutor = retryExecutor;
        this.circuitBreaker = circuitBreaker;
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public PaymentResponse createPayment(PaymentRequest request, String clientKey) {
        String rid = MDC.get("requestId");

        if (!rateLimiter.tryAcquire(clientKey)) {
            log.warn("请求被限流 requestId={} clientKey={} reason=permits-per-window-exceeded", rid, clientKey);
            throw new GatewayException(GatewayErrorCode.RATE_LIMITED,
                    "客户端 " + clientKey + " 的调用超过配额，请稍后再试");
        }

        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new GatewayException(GatewayErrorCode.UPSTREAM_BAD_REQUEST,
                    "缺少幂等键 idempotencyKey");
        }

        VersionDecision decision = resolveVersion(request.version());
        MDC.put("apiVersion", decision.version().code());
        MDC.put("versionBasis", decision.basis());
        log.info("版本判定 requestId={} requestedVersion={} resolvedVersion={} basis={} supported={}",
                rid, request.version(), decision.version().code(), decision.basis(),
                properties.getVersion().getSupported());

        String requestHash = hashRequest(request, decision.version());
        Map<String, Object> payload = buildPayload(request);

        IdempotentResult<NormalizedPayment> result = idempotencyStore.execute(
                request.idempotencyKey(), requestHash,
                () -> callUpstreamOnce(decision.version(), payload, request.testScenario()));

        if (result.error() != null) {
            log.info("返回错误结果 requestId={} version={} replay={} errorCode={}",
                    rid, decision.version().code(), result.replay(), result.error().getErrorCode());
            throw result.error();
        }
        log.info("返回成功结果 requestId={} version={} basis={} replay={} transactionId={}",
                rid, decision.version().code(), decision.basis(), result.replay(),
                result.value().transactionId());
        return new PaymentResponse(result.value(), result.replay());
    }

    private NormalizedPayment callUpstreamOnce(ApiVersion version,
                                               Map<String, Object> payload,
                                               String scenario) {
        String rid = MDC.get("requestId");
        circuitBreaker.allowRequest();
        try {
            NormalizedPayment payment = retryExecutor.execute(() -> {
                JsonNode raw = upstreamClient.createPayment(version, payload, scenario);
                return normalizer.normalize(version, raw);
            });
            circuitBreaker.recordSuccess();
            return payment;
        } catch (GatewayException e) {
            recordCircuitFailureIfNeeded(rid, version, e);
            throw e;
        }
    }

    private void recordCircuitFailureIfNeeded(String rid, ApiVersion version, GatewayException e) {
        GatewayErrorCode code = e.getErrorCode();
        boolean upstreamInfraFailure = switch (code) {
            case UPSTREAM_TIMEOUT, UPSTREAM_SERVER_ERROR, UPSTREAM_BAD_GATEWAY -> true;
            default -> false;
        };
        if (upstreamInfraFailure) {
            circuitBreaker.recordFailure();
            log.warn("记录上游基础设施失败用于熔断 requestId={} version={} errorCode={}",
                    rid, version.code(), code);
        }
    }

    private VersionDecision resolveVersion(String requested) {
        if (requested == null || requested.isBlank()) {
            ApiVersion fallback = ApiVersion.fromCode(properties.getVersion().getDefaultVersion())
                    .orElseThrow(() -> new GatewayException(GatewayErrorCode.VERSION_UNSUPPORTED,
                            "默认版本配置不合法: " + properties.getVersion().getDefaultVersion()));
            if (!isSupported(fallback)) {
                throw new GatewayException(GatewayErrorCode.VERSION_UNSUPPORTED,
                        "默认版本不在网关支持范围内: " + fallback.code());
            }
            return new VersionDecision(fallback, "default-config");
        }
        ApiVersion version = ApiVersion.fromCode(requested)
                .orElseThrow(() -> new GatewayException(GatewayErrorCode.VERSION_UNSUPPORTED,
                        "无法识别的接口版本: " + requested));
        if (!isSupported(version)) {
            throw new GatewayException(GatewayErrorCode.VERSION_UNSUPPORTED,
                    "网关当前不支持接口版本: " + requested + "，支持范围: "
                            + properties.getVersion().getSupported());
        }
        return new VersionDecision(version, "client-specified");
    }

    private boolean isSupported(ApiVersion version) {
        return properties.getVersion().getSupported().contains(version.code());
    }

    private Map<String, Object> buildPayload(PaymentRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderRef", request.orderRef());
        payload.put("amount", request.amount());
        payload.put("currency", request.currency());
        return payload;
    }

    private String hashRequest(PaymentRequest request, ApiVersion version) {
        String raw = String.join("|",
                String.valueOf(request.orderRef()),
                String.valueOf(request.amount()),
                String.valueOf(request.currency()),
                version.code());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR, "无法计算请求摘要", e);
        }
    }

    private record VersionDecision(ApiVersion version, String basis) {
    }
}
