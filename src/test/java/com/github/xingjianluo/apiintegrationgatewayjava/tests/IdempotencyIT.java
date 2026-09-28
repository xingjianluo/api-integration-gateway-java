package com.github.xingjianluo.apiintegrationgatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.xingjianluo.apiintegrationgatewayjava.idempotency.InMemoryIdempotencyStore;
import com.github.xingjianluo.apiintegrationgatewayjava.ratelimit.FixedWindowRateLimiter;
import com.github.xingjianluo.apiintegrationgatewayjava.resilience.DefaultCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.mock.v2-latency=200ms",
                "gateway.rate-limit.permits-per-window=1000"
        })
class IdempotencyIT {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private InMemoryIdempotencyStore idempotencyStore;
    @Autowired
    private FixedWindowRateLimiter rateLimiter;
    @Autowired
    private DefaultCircuitBreaker circuitBreaker;

    @BeforeEach
    void reset() {
        idempotencyStore.clear();
        rateLimiter.clear();
        circuitBreaker.reset();
        rest.postForEntity("/mock-upstream/admin/reset", null, Void.class);
    }

    @Test
    void sequential_replays_return_first_result_and_hit_upstream_once() {
        String key = "idem-seq-" + UUID.randomUUID();

        ResponseEntity<JsonNode> first = post(key, "ord-seq", 100, "USD", "c1", "v1");
        ResponseEntity<JsonNode> second = post(key, "ord-seq", 100, "USD", "c1", "v1");
        ResponseEntity<JsonNode> third = post(key, "ord-seq", 100, "USD", "c1", "v1");

        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(first.getBody().get("idempotentReplay").asBoolean()).isFalse();
        assertThat(second.getBody().get("idempotentReplay").asBoolean()).isTrue();
        assertThat(third.getBody().get("idempotentReplay").asBoolean()).isTrue();

        String firstTxn = first.getBody().get("payment").get("transactionId").asText();
        assertThat(second.getBody().get("payment").get("transactionId").asText()).isEqualTo(firstTxn);
        assertThat(third.getBody().get("payment").get("transactionId").asText()).isEqualTo(firstTxn);

        JsonNode stats = upstreamCalls();
        assertThat(stats.get("callsByScenario").get("success").asInt()).isEqualTo(1);
    }

    @Test
    void concurrent_duplicate_submissions_call_upstream_only_once_and_agree() throws Exception {
        String key = "idem-conc-" + UUID.randomUUID();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ConcurrentLinkedQueue<String> transactionIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Integer> statuses = new ConcurrentLinkedQueue<>();
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            CountDownAndRun barrier = new CountDownAndRun(threads);
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit((Callable<Void>) () -> {
                    barrier.await();
                    ResponseEntity<JsonNode> resp = post(key, "ord-conc", 100, "USD",
                            "c-conc", "v2");
                    statuses.add(resp.getStatusCode().value());
                    if (resp.getStatusCode().is2xxSuccessful()) {
                        transactionIds.add(resp.getBody().get("payment").get("transactionId").asText());
                    }
                    return null;
                }));
            }
            barrier.release();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).hasSize(threads);
        assertThat(statuses).allMatch(s -> s == 200);
        assertThat(transactionIds).hasSize(threads);
        assertThat(transactionIds).containsOnly("MOCK-ord-conc");

        JsonNode stats = upstreamCalls();
        assertThat(stats.get("callsByScenario").get("success").asInt())
                .as("并发重复提交只能真正打到上游一次").isEqualTo(1);
        assertThat(stats.get("maxInFlight").asInt())
                .as("上游同一时刻最多一个在途请求").isLessThanOrEqualTo(1);
    }

    @Test
    void same_key_with_different_body_is_conflict() {
        String key = "idem-conflict-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = post(key, "ord-a", 100, "USD", "c2", "v1");
        ResponseEntity<JsonNode> conflict = post(key, "ord-a", 200, "USD", "c2", "v1");

        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().get("errorCode").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void failed_first_request_is_cached_and_replayed_identically() {
        String key = "idem-fail-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = post(key, "ord-fail", 100, "USD", "c3", "v1",
                "missing-field");
        ResponseEntity<JsonNode> second = post(key, "ord-fail", 100, "USD", "c3", "v1",
                "missing-field");

        assertThat(first.getBody().get("errorCode").asText()).isEqualTo("UPSTREAM_MISSING_FIELD");
        assertThat(second.getBody().get("errorCode").asText()).isEqualTo("UPSTREAM_MISSING_FIELD");

        JsonNode stats = upstreamCalls();
        // 缺字段不可重试 -> 仅一次上游调用；重放直接返回首次缓存的失败
        assertThat(stats.get("callsByScenario").get("missing-field").asInt()).isEqualTo(1);
    }

    private JsonNode upstreamCalls() {
        return rest.getForEntity("/mock-upstream/admin/calls", JsonNode.class).getBody();
    }

    private ResponseEntity<JsonNode> post(String key, String orderRef, int amount,
                                          String currency, String client, String version) {
        return post(key, orderRef, amount, currency, client, version, "success");
    }

    private ResponseEntity<JsonNode> post(String key, String orderRef, int amount,
                                          String currency, String client, String version,
                                          String scenario) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Client-Id", client);
        h.add("X-Api-Version", version);
        h.add("X-Test-Scenario", scenario);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", key);
        body.put("orderRef", orderRef);
        body.put("amount", amount);
        body.put("currency", currency);
        return rest.exchange("/api/v1/payments", HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
    }

    /** 让所有线程尽量在同一时刻发起请求，放大并发窗口。 */
    private static final class CountDownAndRun {
        private final java.util.concurrent.CountDownLatch ready;
        private final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);

        private CountDownAndRun(int threads) {
            this.ready = new java.util.concurrent.CountDownLatch(threads);
        }

        void await() throws InterruptedException {
            ready.countDown();
            ready.await();
            go.await();
        }

        void release() throws InterruptedException {
            ready.await();
            Thread.sleep(50);
            go.countDown();
        }
    }
}
