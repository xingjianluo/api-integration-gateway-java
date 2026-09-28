package com.github.xingjianluo.apiintegrationgatewayjava.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.mock.MockUpstreamController;
import com.github.xingjianluo.apiintegrationgatewayjava.version.ApiVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 通过 HTTP 调用（本地模拟的）第三方上游，并把所有异常面归一为 {@link GatewayException}，
 * 绝不把上游/底层原始异常直接抛给上层：
 * <ul>
 *   <li>读/连接超时 -> UPSTREAM_TIMEOUT（可重试）。不同 HTTP 客户端的超时表现不同：
 *       JDK HttpClient 可能抛 CancellationException，HttpURLConnection 抛包裹
 *       SocketTimeoutException 的 ResourceAccessException，这里统一识别；</li>
 *   <li>5xx -> UPSTREAM_SERVER_ERROR / UPSTREAM_BAD_GATEWAY（可重试）；</li>
 *   <li>400 -> UPSTREAM_BAD_REQUEST；404（版本不支持）-> VERSION_UNSUPPORTED；</li>
 *   <li>响应体无法解析 / Content-Type 不是 JSON -> UPSTREAM_MALFORMED_RESPONSE；</li>
 *   <li>其它未知异常 -> INTERNAL_ERROR。</li>
 * </ul>
 */
@Component
public class RestUpstreamPaymentClient implements UpstreamPaymentClient {

    private static final Logger log = LoggerFactory.getLogger(RestUpstreamPaymentClient.class);

    private final RestClient restClient;
    private final GatewayProperties properties;
    private final UpstreamBaseUrlResolver baseUrlResolver;

    public RestUpstreamPaymentClient(RestClient restClient,
                                     GatewayProperties properties,
                                     UpstreamBaseUrlResolver baseUrlResolver) {
        this.restClient = restClient;
        this.properties = properties;
        this.baseUrlResolver = baseUrlResolver;
    }

    @Override
    public JsonNode createPayment(ApiVersion version, Map<String, Object> payload, String scenario) {
        String url = baseUrlResolver.resolve() + properties.getUpstream().getPaymentsPath();
        String rid = MDC.get("requestId");
        try {
            JsonNode body = restClient.post()
                    .uri(url)
                    .header(MockUpstreamController.VERSION_HEADER, version.code())
                    .headers(h -> {
                        if (scenario != null && !scenario.isBlank()) {
                            h.add(MockUpstreamController.SCENARIO_HEADER, scenario);
                        }
                    })
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            log.info("上游调用成功 requestId={} version={} scenario={} url={}",
                    rid, version.code(), scenario, url);
            return body;
        } catch (RuntimeException e) {
            throw mapException(version, scenario, e);
        }
    }

    private GatewayException mapException(ApiVersion version, String scenario, RuntimeException e) {
        String rid = MDC.get("requestId");
        if (isTimeout(e)) {
            log.warn("上游调用超时 requestId={} version={} scenario={} reason=read/connect-timeout exType={}",
                    rid, version.code(), scenario, e.getClass().getSimpleName());
            return new GatewayException(GatewayErrorCode.UPSTREAM_TIMEOUT,
                    "上游服务调用超时 (version=" + version.code() + ")", e);
        }
        if (!(e instanceof RestClientException rce)) {
            log.error("上游调用发生未预期异常 requestId={} version={} scenario={} exType={}",
                    rid, version.code(), scenario, e.getClass().getName(), e);
            return new GatewayException(GatewayErrorCode.INTERNAL_ERROR,
                    "调用上游时发生内部错误", e);
        }

        HttpStatusCode status = extractStatus(rce);
        if (status != null) {
            GatewayErrorCode code = mapStatus(status);
            log.warn("上游返回非成功状态 requestId={} version={} scenario={} status={} reason={}",
                    rid, version.code(), scenario, status.value(), code.name());
            return new GatewayException(code,
                    "上游返回非成功状态: HTTP " + status.value()
                            + " (version=" + version.code() + ")", rce);
        }
        if (isBodyParseFailure(rce)) {
            log.warn("上游响应无法解析 requestId={} version={} scenario={} reason=malformed-json",
                    rid, version.code(), scenario);
            return new GatewayException(GatewayErrorCode.UPSTREAM_MALFORMED_RESPONSE,
                    "上游响应不是合法的 JSON (version=" + version.code() + ")", rce);
        }
        log.error("上游调用发生 I/O 异常 requestId={} version={} scenario={} exType={}",
                rid, version.code(), scenario, rce.getClass().getName(), rce);
        return new GatewayException(GatewayErrorCode.UPSTREAM_BAD_GATEWAY,
                "无法与上游建立有效通信 (version=" + version.code() + ")", rce);
    }

    private GatewayErrorCode mapStatus(HttpStatusCode status) {
        int code = status.value();
        if (code == 404) {
            return GatewayErrorCode.VERSION_UNSUPPORTED;
        }
        if (code == 400 || code == 422) {
            return GatewayErrorCode.UPSTREAM_BAD_REQUEST;
        }
        if (code == 502 || code == 503 || code == 504) {
            return GatewayErrorCode.UPSTREAM_BAD_GATEWAY;
        }
        if (code >= 500) {
            return GatewayErrorCode.UPSTREAM_SERVER_ERROR;
        }
        return GatewayErrorCode.UPSTREAM_BAD_REQUEST;
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            String type = cur.getClass().getName();
            if (type.equals("java.net.SocketTimeoutException")
                    || type.equals("java.net.http.HttpTimeoutException")
                    || type.equals("java.util.concurrent.CancellationException")
                    || type.equals("java.util.concurrent.TimeoutException")) {
                return true;
            }
            if (cur.getMessage() != null && cur.getMessage().toLowerCase().contains("timeout")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private boolean isBodyParseFailure(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            String type = cur.getClass().getName();
            if (type.contains("HttpMessageNotReadableException")
                    || type.contains("MismatchedInputException")
                    || type.contains("JsonParseException")
                    || type.contains("UnknownContentTypeException")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private HttpStatusCode extractStatus(RestClientException e) {
        if (e instanceof HttpStatusCodeException hsce) {
            return hsce.getStatusCode();
        }
        return null;
    }
}
