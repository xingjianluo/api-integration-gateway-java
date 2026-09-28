package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.apiintegrationgatewayjava.ratelimit.FixedWindowRateLimiter;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.DefaultCircuitBreaker;
import com.github.xingjianluo.apiintegrationgatewayjava.service.DefaultPaymentGatewayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证关键决策日志里包含：请求标识(requestId)、命中的接口版本(version)、
 * 版本判定依据(basis)。MDC 字段由 RequestIdFilter / Service 填充，日志 pattern 也带这些键。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.rate-limit.permits-per-window=1000",
                "gateway.mock.v2-latency=0ms"
        })
class LoggingContentIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private InMemoryIdempotencyStore idempotencyStore;
    @Autowired
    private FixedWindowRateLimiter rateLimiter;
    @Autowired
    private DefaultCircuitBreaker circuitBreaker;

    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void setup() {
        idempotencyStore.clear();
        rateLimiter.clear();
        circuitBreaker.reset();

        serviceLogger = (Logger) LoggerFactory.getLogger(DefaultPaymentGatewayService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
    }

    @Test
    void logs_contain_request_id_version_and_decision_basis() {
        ResponseEntity<JsonNode> resp = post("v2", "key-log-1");
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        List<ILoggingEvent> events = appender.list;
        assertThat(events).isNotEmpty();

        ILoggingEvent decision = events.stream()
                .filter(e -> e.getFormattedMessage().contains("版本判定"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少版本判定日志"));

        // MDC 中的请求标识、版本、判定依据
        assertThat(decision.getMDCPropertyMap().get("requestId")).isNotBlank();
        assertThat(decision.getMDCPropertyMap().get("apiVersion")).isEqualTo("v2");
        assertThat(decision.getMDCPropertyMap().get("versionBasis")).isEqualTo("client-specified");
        assertThat(decision.getFormattedMessage())
                .contains("requestId=")
                .contains("resolvedVersion=v2")
                .contains("basis=client-specified");

        // 响应头也回带请求标识
        assertThat(resp.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void default_version_basis_is_logged_when_version_omitted() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Client-Id", "log-client");
        h.add("X-Test-Scenario", "success");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", "key-log-default");
        body.put("orderRef", "ord-log-default");
        body.put("amount", 100);
        body.put("currency", "USD");
        ResponseEntity<JsonNode> resp = rest.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        ILoggingEvent decision = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("版本判定"))
                .findFirst()
                .orElseThrow();
        assertThat(decision.getMDCPropertyMap().get("apiVersion")).isEqualTo("v1");
        assertThat(decision.getMDCPropertyMap().get("versionBasis")).isEqualTo("default-config");
    }

    private ResponseEntity<JsonNode> post(String version, String key) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Client-Id", "log-client");
        h.add("X-Api-Version", version);
        h.add("X-Test-Scenario", "success");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", key);
        body.put("orderRef", key);
        body.put("amount", 100);
        body.put("currency", "USD");
        return rest.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
    }
}
