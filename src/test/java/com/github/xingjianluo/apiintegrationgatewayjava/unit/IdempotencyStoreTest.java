package com.github.xingjianluo.apiintegrationgatewayjava.unit;

import com.github.xingjianluo.apiintegrationgatewayjava.config.GatewayProperties;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayErrorCode;
import com.github.xingjianluo.apiintegrationgatewayjava.core.GatewayException;
import com.github.xingjianluo.apiintegrationgatewayjava.idempotency.IdempotentResult;
import com.github.xingjianluo.apiintegrationgatewayjava.idempotency.InMemoryIdempotencyStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyStoreTest {

    private InMemoryIdempotencyStore store(Duration ttl, Duration acquireTimeout) {
        GatewayProperties props = new GatewayProperties();
        props.getIdempotency().setTtl(ttl);
        props.getIdempotency().setAcquireTimeout(acquireTimeout);
        return new InMemoryIdempotencyStore(props);
    }

    @Test
    void first_call_executes_and_second_is_replay() {
        InMemoryIdempotencyStore store = store(Duration.ofMinutes(1), Duration.ofSeconds(1));
        AtomicInteger executions = new AtomicInteger();

        IdempotentResult<String> first = store.execute("k1", "h1", () -> {
            executions.incrementAndGet();
            return "v1";
        });
        IdempotentResult<String> second = store.execute("k1", "h1", () -> {
            executions.incrementAndGet();
            return "v2";
        });

        assertThat(first.value()).isEqualTo("v1");
        assertThat(first.replay()).isFalse();
        assertThat(second.value()).isEqualTo("v1");
        assertThat(second.replay()).isTrue();
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void different_hash_with_same_key_conflicts() {
        InMemoryIdempotencyStore store = store(Duration.ofMinutes(1), Duration.ofSeconds(1));
        store.execute("k", "hash-a", () -> "x");
        try {
            store.execute("k", "hash-b", () -> "y");
            throw new AssertionError("expected conflict");
        } catch (GatewayException e) {
            assertThat(e.getErrorCode()).isEqualTo(GatewayErrorCode.IDEMPOTENCY_CONFLICT);
        }
    }

    @Test
    void cached_failure_is_replayed_without_re_execution() {
        InMemoryIdempotencyStore store = store(Duration.ofMinutes(1), Duration.ofSeconds(1));
        AtomicInteger executions = new AtomicInteger();

        Runnable failing = () -> store.execute("kf", "h", () -> {
            executions.incrementAndGet();
            throw new GatewayException(GatewayErrorCode.UPSTREAM_TIMEOUT, "boom");
        });

        try {
            failing.run();
        } catch (GatewayException ignored) {
        }
        try {
            failing.run();
        } catch (GatewayException ignored) {
        }

        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    void concurrent_calls_execute_action_once_and_all_get_same_value() throws Exception {
        InMemoryIdempotencyStore store = store(Duration.ofMinutes(1), Duration.ofSeconds(5));
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch inAction = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    IdempotentResult<String> r = store.execute("conc", "hh", () -> {
                        executions.incrementAndGet();
                        inAction.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ie);
                        }
                        return "same";
                    });
                    return r.value() + ":" + r.replay();
                }));
            }
            assertThat(inAction.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            int leaders = 0;
            int followers = 0;
            for (Future<String> f : futures) {
                String r = f.get(5, TimeUnit.SECONDS);
                assertThat(r).startsWith("same:");
                if (r.endsWith("false")) {
                    leaders++;
                } else if (r.endsWith("true")) {
                    followers++;
                }
            }
            assertThat(leaders).isEqualTo(1);
            assertThat(followers).isEqualTo(7);
        } finally {
            pool.shutdownNow();
        }
        assertThat(executions.get()).isEqualTo(1);
    }
}
