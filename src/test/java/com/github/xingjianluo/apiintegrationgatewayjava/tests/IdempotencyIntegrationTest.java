package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 幂等：同一幂等键顺序重放与并发重复提交都只真正打到上游一次，
 * 且每次返回与首次完全一致（含 requestId）。
 */
@TestPropertySource(properties = "gateway.rate-limit.permits-per-window=1000")
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    private JsonNode query(String client, String key, String orderId, String version) {
        return http.post()
                .uri("/api/gateway/orders/" + orderId + "/query")
                .header("X-Client-Id", client)
                .header("X-Idempotency-Key", key)
                .header("X-Api-Version", version)
                .retrieve().body(JsonNode.class);
    }

    @Test
    void sequential_replay_hits_upstream_once_and_returns_identical_result() {
        String key = UUID.randomUUID().toString();
        JsonNode first = query("c-seq", key, "ord-1", "v2");
        JsonNode second = query("c-seq", key, "ord-1", "v2");
        JsonNode third = query("c-seq", key, "ord-1", "v2");

        assertTrue(first.path("success").asBoolean());
        assertFalse(first.path("replay").asBoolean());
        assertTrue(second.path("replay").asBoolean());
        assertTrue(third.path("replay").asBoolean());

        // 结果一致（含同一 requestId 与订单内容）
        assertEquals(first.path("requestId").asText(), second.path("requestId").asText());
        assertEquals(first.path("requestId").asText(), third.path("requestId").asText());
        assertEquals(first.path("order").toString(), second.path("order").toString());
        assertEquals(first.path("order").toString(), third.path("order").toString());
        assertEquals("V2", first.path("apiVersion").asText());

        // 上游只被真正调用一次
        assertEquals(1, upstreamTotalInvocations());
    }

    @Test
    void concurrent_duplicates_share_single_upstream_call() throws Exception {
        String key = UUID.randomUUID().toString();
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<JsonNode>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> query("c-conc", key, "ord-2", "v3"));
        }
        List<Future<JsonNode>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        List<JsonNode> results = new ArrayList<>();
        for (Future<JsonNode> f : futures) {
            results.add(f.get());
        }
        long replays = results.stream().filter(r -> r.path("replay").asBoolean()).count();
        long firsts = results.stream().filter(r -> !r.path("replay").asBoolean()).count();

        assertEquals(1, firsts, "只有一个请求是首次执行");
        assertEquals(threads - 1, replays, "其余全部命中幂等");
        String referenceRequestId = results.get(0).path("requestId").asText();
        for (JsonNode r : results) {
            assertEquals(referenceRequestId, r.path("requestId").asText());
            assertEquals(results.get(0).path("order").toString(), r.path("order").toString());
        }
        // 并发重复提交也绝不允许第二次上游调用。
        assertEquals(1, upstreamTotalInvocations());
    }

    @Test
    void different_keys_are_charged_separately() {
        JsonNode a = query("c-multi", UUID.randomUUID().toString(), "ord-3", "v1");
        JsonNode b = query("c-multi", UUID.randomUUID().toString(), "ord-3", "v1");
        assertTrue(a.path("success").asBoolean());
        assertTrue(b.path("success").asBoolean());
        assertFalse(a.path("replay").asBoolean());
        assertFalse(b.path("replay").asBoolean());
        assertEquals(2, upstreamTotalInvocations());
    }
}
