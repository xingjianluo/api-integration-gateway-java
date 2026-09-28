package com.github.xingjianluo.apiintegrationgatewayjava.gateway.web;

import com.github.xingjianluo.apiintegrationgatewayjava.gateway.GatewayService;
import com.github.xingjianluo.apiintegrationgatewayjava.model.GatewayResponse;
import org.springframework.web.bind.annotation.*;

/**
 * 对外接入层接口。
 */
@RestController
@RequestMapping("/api/gateway/orders")
public class OrderController {

    private final GatewayService gatewayService;

    public OrderController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @PostMapping("/{orderId}/query")
    public GatewayResponse query(@PathVariable String orderId,
                                 @RequestHeader(value = "X-Client-Id", defaultValue = "anonymous") String clientId,
                                 @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
                                 @RequestHeader(value = "X-Api-Version", required = false) String version,
                                 @RequestHeader(value = "X-Mock-Mode", required = false) String mockMode) {
        return gatewayService.queryOrder(clientId, idempotencyKey, version, orderId, mockMode);
    }
}
