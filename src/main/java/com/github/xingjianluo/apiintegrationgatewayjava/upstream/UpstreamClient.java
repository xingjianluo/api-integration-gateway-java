package com.github.xingjianluo.apiintegrationgatewayjava.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.model.ApiVersion;
import com.github.xingjianluo.apiintegrationgatewayjava.model.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.model.MockMode;
import com.github.xingjianluo.apiintegrationgatewayjava.model.RejectReason;
import com.github.xingjianluo.apiintegrationgatewayjava.model.UpstreamErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;

/**
 * 调用本地模拟上游。所有失败都归一成 {@link GatewayException} +
 * {@link UpstreamErrorCode}，上游原始异常只作为 cause 记录、不向外透出报文/堆栈。
 */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;

    public UpstreamClient(RestClient upstreamRestClient,
                          ObjectMapper objectMapper,
                          GatewayProperties properties) {
        this.restClient = upstreamRestClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 单次调用上游（不含重试，重试由上层负责）。
     */
    public JsonNode fetchOrder(String orderId, ApiVersion version, String requestId, String mockMode) {
        String path = "/mock-upstream/" + version.name().toLowerCase() + "/orders/" + orderId;
        String base = properties.getUpstream().getBaseUrl();
        String uri = (base == null || base.isBlank()) ? path : base.trim() + path;
        log.debug("调用上游 requestId={} uri={}", requestId, uri);
        try {
            String body = restClient.get()
                    .uri(uri)
                    .header("X-Mock-Mode", mockMode == null ? MockMode.NORMAL.name() : mockMode)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (RestClientResponseException e) {
            throw classifyHttpStatus(e.getStatusCode(), requestId, e);
        } catch (ResourceAccessException e) {
            // 连接/读超时等传输层异常。
            if (isTimeout(e)) {
                throw gateway(UpstreamErrorCode.UPSTREAM_TIMEOUT, null, requestId,
                        "上游调用超时", e);
            }
            throw gateway(UpstreamErrorCode.UPSTREAM_INTERNAL_ERROR, null, requestId,
                    "上游连接异常", e);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw gateway(UpstreamErrorCode.UPSTREAM_INTERNAL_ERROR, null, requestId,
                    "上游响应解析失败", e);
        }
    }

    private GatewayException classifyHttpStatus(HttpStatusCode status, String requestId, Exception cause) {
        if (status.value() == 404) {
            return gateway(UpstreamErrorCode.UPSTREAM_NOT_FOUND,
                    RejectReason.UPSTREAM_ORDER_NOT_FOUND, requestId,
                    "上游返回订单不存在(404)", cause);
        }
        return gateway(UpstreamErrorCode.UPSTREAM_BAD_STATUS_TRANSIENT, null, requestId,
                "上游返回非成功状态: " + status.value(), cause);
    }

    private boolean isTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private GatewayException gateway(UpstreamErrorCode code, RejectReason reason,
                                     String requestId, String message, Throwable cause) {
        // 日志保留内部细节；对外异常消息不携带原始堆栈。
        log.warn("上游失败归一 requestId={} code={} reason={} msg={}",
                requestId, code, reason, message);
        return new GatewayException(code, reason, requestId, message, cause);
    }
}
