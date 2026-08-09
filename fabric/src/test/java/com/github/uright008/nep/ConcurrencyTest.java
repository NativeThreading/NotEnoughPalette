package com.github.uright008.nep;

import com.github.uright008.nep.palette.OptimizedPalettedContainer;
import net.minecraft.ReportedException;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Concurrency tests for {@link OptimizedPalettedContainer}.
 *
 * <p>The write path ({@code set}/{@code getAndSet}/{@code write}/{@code read})
 * goes through {@code acquire()}/{@code release()} which is backed by
 * {@link net.minecraft.util.ThreadingDetector}. The read path
 * ({@code get}) and {@code getAndSetUnchecked} are deliberately lock-free.
 *
 * <p>{@link ThreadingDetector} semantics verified here: when thread B calls
 * {@code checkAndLock()} while thread A holds the lock, B <em>blocks</em> on the
 * internal semaphore; A's next {@code checkAndUnlock()} then throws a
 * {@link ReportedException} and B throws the same exception once unblocked. The
 * message is {@code "Accessing OptimizedPalettedContainer from multiple threads"}
 * with an {@link IllegalStateException} cause.</p>
 */
class ConcurrencyTest {

    private static final String AIR = "air";
    private static final String STONE = "stone";

    private static Strategy<String> stringStrategy() {
        IdMap<String> idMap = TestIdMap.blockStates(AIR, STONE, "dirt", "water");
        return Strategy.createForBlockStates(idMap);
    }

    private static OptimizedPalettedContainer<String> freshContainer() {
        return new OptimizedPalettedContainer<>(AIR, stringStrategy());
    }

    @Test
    void singleThread_getSet_getAndSet_correct() {
        OptimizedPalettedContainer<String> container = freshContainer();

        assertThat(container.get(0, 0, 0)).isEqualTo(AIR);
        assertThat(container.getAndSet(1, 2, 3, STONE)).isEqualTo(AIR);
        assertThat(container.get(1, 2, 3)).isEqualTo(STONE);

        container.set(1, 2, 3, "dirt");
        assertThat(container.get(1, 2, 3)).isEqualTo("dirt");
        assertThat(container.get(15, 15, 15)).isEqualTo(AIR); // untouched corner
    }

    @Test
    void concurrentSet_throwsThreadingDetector() throws Exception {
        OptimizedPalettedContainer<String> container = freshContainer();
        container.acquire(); // main thread holds the container lock

        AtomicReference<Throwable> secondThreadThrown = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                container.set(0, 0, 0, STONE);
            } catch (Throwable throwable) {
                secondThreadThrown.set(throwable);
            }
        });
        second.setDaemon(true);
        second.start();
        awaitBlockedInLock(second);

        // Releasing while the second thread is blocked in checkAndLock must throw.
        Throwable mainThrown = assertThrows(ReportedException.class, container::release);
        second.join(5_000);

        assertThat(secondThreadThrown.get()).isInstanceOf(ReportedException.class);
        assertThat(mainThrown.getMessage()).isEqualTo("Accessing OptimizedPalettedContainer from multiple threads");
        assertThat(mainThrown.getCause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Accessing OptimizedPalettedContainer from multiple threads");
    }

    @Test
    void concurrentGetAndSet_throwsThreadingDetector() throws Exception {
        OptimizedPalettedContainer<String> container = freshContainer();
        container.acquire();

        AtomicReference<Throwable> secondThreadThrown = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                container.getAndSet(0, 0, 0, STONE);
            } catch (Throwable throwable) {
                secondThreadThrown.set(throwable);
            }
        });
        second.setDaemon(true);
        second.start();
        awaitBlockedInLock(second);

        assertThrows(ReportedException.class, container::release);
        second.join(5_000);

        assertThat(secondThreadThrown.get()).isInstanceOf(ReportedException.class);
        assertThat(secondThreadThrown.get().getMessage())
                .isEqualTo("Accessing OptimizedPalettedContainer from multiple threads");
    }

    @Test
    void concurrentRead_isLockFree_noException() throws Exception {
        OptimizedPalettedContainer<String> container = freshContainer();
        // Grow to IndirectStorage so reads actually walk the shared ids array.
        for (int i = 0; i < 64; i++) {
            int x = i & 15;
            int z = (i >> 4) & 15;
            int y = (i >> 8) & 15;
            container.set(x, y, z, i % 3 == 0 ? STONE : i % 3 == 1 ? "dirt" : "water");
        }

        int readers = 8;
        int iterations = 2_000;
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < readers; t++) {
            final int threadId = t;
            Thread reader = new Thread(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        int x = (i * 7 + threadId) & 15;
                        int y = (i * 3 + threadId) & 15;
                        int z = (i * 11 + threadId) & 15;
                        container.get(x, y, z);
                    }
                } catch (Throwable throwable) {
                    failed.set(true);
                }
            });
            reader.setDaemon(true);
            threads.add(reader);
            reader.start();
        }

        for (Thread reader : threads) {
            reader.join(10_000);
        }

        assertThat(failed).isFalse();
    }

    @Test
    void volatileVisibility_writerThenReader() throws Exception {
        OptimizedPalettedContainer<String> container = freshContainer();

        Thread writer = new Thread(() -> container.set(5, 6, 7, STONE));
        writer.setDaemon(true);
        writer.start();
        writer.join(5_000);

        // The volatile storage field makes the worker's write visible to this thread.
        assertThat(container.get(5, 6, 7)).isEqualTo(STONE);
    }

    @Test
    void getAndSetUnchecked_skipsLock() throws Exception {
        OptimizedPalettedContainer<String> container = freshContainer();
        container.acquire(); // main thread holds the container lock

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                container.getAndSetUnchecked(3, 4, 5, STONE);
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        });
        second.setDaemon(true);
        second.start();
        second.join(5_000);

        // getAndSetUnchecked must not acquire: it completes even while the lock
        // is held. If it ever started acquiring, it would block forever here.
        assertThat(second.isAlive()).isFalse();
        assertThat(thrown.get()).isNull();
        assertThat(container.get(3, 4, 5)).isEqualTo(STONE);

        // No contention was ever registered, so a clean release succeeds.
        container.release();
    }

    /**
     * Waits until {@code thread} is parked inside {@code ThreadingDetector}'s
     * semaphore acquire. Since the container lock is held, the thread is
     * guaranteed to reach {@code Thread.State.WAITING} eventually.
     */
    private static void awaitBlockedInLock(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("thread did not block in the container lock");
            }
            Thread.yield();
        }
    }
}
