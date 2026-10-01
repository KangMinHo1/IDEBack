package com.myide.backend.service.saferun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시에 도는 실행 수가 정한 개수를 넘지 않는지 확인한다.
 *
 * 여기가 틀리면 2GB 서버에서 Spring Boot 가 두 개 겹쳐 서버가 메모리 부족으로 죽는다.
 */
class RunQueueTest {

    private static CompletableFuture<Boolean> acquireAsync(RunQueue queue, AtomicBoolean cancelled, AtomicInteger waits) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return queue.acquire(cancelled::get, waits::incrementAndGet);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("자리가 있으면 기다리지 않고 바로 들어간다")
    void entersImmediatelyWhenFree() throws Exception {
        RunQueue queue = new RunQueue(1);
        AtomicInteger waits = new AtomicInteger();

        assertThat(queue.acquire(() -> false, waits::incrementAndGet)).isTrue();
        assertThat(waits).hasValue(0);
    }

    @Test
    @DisplayName("자리가 없으면 기다린다고 한 번 알리고, 자리가 나면 들어간다")
    void waitsUntilReleased() throws Exception {
        RunQueue queue = new RunQueue(1);
        AtomicInteger waits = new AtomicInteger();

        assertThat(queue.acquire(() -> false, () -> {})).isTrue();

        CompletableFuture<Boolean> second = acquireAsync(queue, new AtomicBoolean(), waits);

        // 앞사람이 아직 돌고 있는 동안에는 들어가지 못한다.
        Thread.sleep(700);
        assertThat(second).isNotDone();
        assertThat(waits).hasValue(1);

        queue.release();

        assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(waits).hasValue(1);
    }

    @Test
    @DisplayName("기다리다 그만두면 자리를 얻지 않고 돌아온다")
    void cancelledWaiterGivesUp() throws Exception {
        RunQueue queue = new RunQueue(1);
        AtomicBoolean cancelled = new AtomicBoolean();

        queue.acquire(() -> false, () -> {});
        CompletableFuture<Boolean> waiter = acquireAsync(queue, cancelled, new AtomicInteger());

        cancelled.set(true);
        assertThat(waiter.get(5, TimeUnit.SECONDS)).isFalse();

        // 그만둔 사람이 자리를 가져가지 않았으니, 자리가 나면 다음 사람이 바로 들어간다.
        queue.release();
        assertThat(queue.acquire(() -> false, () -> {})).isTrue();
    }

    @Test
    @DisplayName("설정한 개수만큼은 동시에 들어간다")
    void honorsConcurrency() throws Exception {
        RunQueue queue = new RunQueue(2);
        AtomicInteger waits = new AtomicInteger();

        assertThat(queue.acquire(() -> false, waits::incrementAndGet)).isTrue();
        assertThat(queue.acquire(() -> false, waits::incrementAndGet)).isTrue();
        assertThat(waits).hasValue(0);

        assertThat(queue.acquire(() -> true, waits::incrementAndGet)).isFalse();
        assertThat(waits).hasValue(1);
    }

    @Test
    @DisplayName("0 이하로 설정해도 한 개는 돈다")
    void atLeastOne() throws Exception {
        assertThat(new RunQueue(0).acquire(() -> false, () -> {})).isTrue();
    }
}
