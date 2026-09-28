package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可观测性：关键决策日志必须包含请求标识(requestId)、命中版本(version)与判定依据(basis)。
 */
@TestPropertySource(properties = "gateway.rate-limit.permits-per-window=1000")
class LoggingObservabilityTest extends AbstractIntegrationTest {

    @Test
    void decision_logs_contain_request_id_version_and_basis() {
        Logger gatewayLogger = (Logger) LoggerFactory.getLogger(
                "com.github.xingjianluo.apiintegrationgatewayjava.gateway.GatewayService");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        gatewayLogger.addAppender(appender);
        try {
            String key = UUID.randomUUID().toString();
            JsonNode first = http.post().uri("/api/gateway/orders/log-1/query")
                    .header("X-Client-Id", "c-log")
                    .header("X-Idempotency-Key", key)
                    .header("X-Api-Version", "v2")
                    .retrieve().body(JsonNode.class);
            String requestId = first.path("requestId").asText();

            JsonNode replay = http.post().uri("/api/gateway/orders/log-1/query")
                    .header("X-Client-Id", "c-log")
                    .header("X-Idempotency-Key", key)
                    .header("X-Api-Version", "v2")
                    .retrieve().body(JsonNode.class);
            assertTrue(replay.path("replay").asBoolean());

            // 首条决策日志：包含 requestId、版本 V2、判定依据 header:v2
            boolean firstLogged = appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains(requestId)
                            && e.getFormattedMessage().contains("version=V2")
                            && e.getFormattedMessage().contains("basis=header:v2")
                            && e.getFormattedMessage().contains("decision=FIRST"));
            assertTrue(firstLogged, "首次决策日志应含 requestId/version/basis");

            // 重放日志：命中幂等的判定依据
            boolean replayLogged = appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains(requestId)
                            && e.getFormattedMessage().contains("decision=REPLAY")
                            && e.getFormattedMessage().contains("basis=idempotency-hit"));
            assertTrue(replayLogged, "重放决策日志应含 idempotency-hit 判定依据");
        } finally {
            gatewayLogger.detachAppender(appender);
        }
    }

    @Test
    void default_version_decision_log_records_header_absent_basis() {
        Logger gatewayLogger = (Logger) LoggerFactory.getLogger(
                "com.github.xingjianluo.apiintegrationgatewayjava.gateway.GatewayService");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        gatewayLogger.addAppender(appender);
        try {
            http.post().uri("/api/gateway/orders/log-2/query")
                    .header("X-Client-Id", "c-log2")
                    .header("X-Idempotency-Key", UUID.randomUUID().toString())
                    .retrieve().body(JsonNode.class);
            boolean logged = appender.list.stream()
                    .anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO)
                            && e.getFormattedMessage().contains("version=V3")
                            && e.getFormattedMessage().contains("basis=header-absent->default:V3"));
            assertTrue(logged, "缺省版本应记录 header-absent->default 判定依据");
        } finally {
            gatewayLogger.detachAppender(appender);
        }
    }
}
