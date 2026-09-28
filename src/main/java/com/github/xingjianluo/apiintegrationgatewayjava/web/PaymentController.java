package com.github.xingjianluo.apiintegrationgatewayjava.web;

import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.service.PaymentGatewayService;
import com.github.xingjianluo.apiintegrationgatewayjava.service.PaymentRequest;
import com.github.xingjianluo.apiintegrationgatewayjava.service.PaymentResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 对外支付接入层。POST /api/v1/payments
 *
 * <p>请求头：
 * <ul>
 *   <li>X-Client-Id：客户端标识，用于按客户端限流，缺省为 anonymous</li>
 *   <li>X-Api-Version：显式指定上游接口版本 v1/v2/v3，缺省走网关默认版本</li>
 *   <li>Idempotency-Key：幂等键（也可放请求体 idempotencyKey）</li>
 *   <li>X-Test-Scenario：仅用于联调/测试，透传给本地模拟上游控制场景</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/v1/payments", produces = MediaType.APPLICATION_JSON_VALUE)
public class PaymentController {

    private final PaymentGatewayService service;

    public PaymentController(PaymentGatewayService service) {
        this.service = service;
    }

    @PostMapping
    public PaymentResponse create(@RequestBody(required = false) PaymentRequestBody body,
                                  @RequestHeader(name = "X-Client-Id", defaultValue = "anonymous")
                                  String clientId,
                                  @RequestHeader(name = "X-Api-Version", required = false)
                                  String headerVersion,
                                  @RequestHeader(name = "Idempotency-Key", required = false)
                                  String headerIdempotencyKey,
                                  @RequestHeader(name = "X-Test-Scenario", required = false)
                                  String testScenario) {
        PaymentRequestBody safe = body == null
                ? new PaymentRequestBody(null, null, null, null, null) : body;
        String idempotencyKey = firstNonBlank(headerIdempotencyKey, safe.idempotencyKey);
        String version = firstNonBlank(headerVersion, safe.version);

        PaymentRequest request = new PaymentRequest(
                idempotencyKey,
                version,
                safe.orderRef,
                safe.amount,
                safe.currency,
                testScenario);
        validate(request);
        return service.createPayment(request, clientId);
    }

    private void validate(PaymentRequest request) {
        if (request.orderRef() == null || request.orderRef().isBlank()) {
            throw new GatewayException(GatewayErrorCode.UPSTREAM_BAD_REQUEST, "缺少字段 orderRef");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new GatewayException(GatewayErrorCode.UPSTREAM_BAD_REQUEST, "字段 amount 必须为正数");
        }
        if (request.currency() == null || request.currency().isBlank()) {
            throw new GatewayException(GatewayErrorCode.UPSTREAM_BAD_REQUEST, "缺少字段 currency");
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    public record PaymentRequestBody(
            String idempotencyKey,
            String version,
            String orderRef,
            BigDecimal amount,
            String currency
    ) {
    }
}
