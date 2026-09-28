package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.idempotency.IdempotencyStore;
import com.github.xingjianluo.apiintegrationgatewayjava.ratelimit.ClientRateLimiter;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.CircuitBreaker;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.LastGoodCache;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * 集成测试基类：Web 环境 + 随机端口，上游 baseUrl 运行时指向同应用内的模拟控制器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "gateway.rate-limit.permits-per-window=1000",
        "gateway.retry.max-attempts=2",
        "gateway.retry.backoff=10ms",
        "gateway.upstream.read-timeout=100ms",
        "gateway.circuit-breaker.failure-threshold=99",
        "gateway.circuit-breaker.open-duration=1s"
})
public abstract class AbstractIntegrationTest {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected GatewayProperties gatewayProperties;
    @Autowired
    protected IdempotencyStore idempotencyStore;
    @Autowired
    protected ClientRateLimiter rateLimiter;
    @Autowired
    protected CircuitBreaker circuitBreaker;
    @Autowired
    protected LastGoodCache lastGoodCache;

    protected RestClient http;

    @BeforeEach
    void setUpCommon() {
        // 让 UpstreamClient 通过绝对地址访问同一应用内的模拟上游。
        gatewayProperties.getUpstream().setBaseUrl("http://localhost:" + port);

        idempotencyStore.reset();
        rateLimiter.reset();
        circuitBreaker.reset();
        lastGoodCache.reset();
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();

        http.post().uri("/mock-upstream/admin/reset").retrieve().toBodilessEntity();
    }

    protected JsonNode stats() {
        String body = http.get().uri("/mock-upstream/admin/stats").retrieve().body(String.class);
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected int upstreamTotalInvocations() {
        return stats().path("total").asInt();
    }
}
