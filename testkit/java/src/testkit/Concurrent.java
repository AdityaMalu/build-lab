package testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Helpers for hammering code from many threads at once. */
public final class Concurrent {
    private Concurrent() {}

    @FunctionalInterface
    public interface Task {
        void run(int threadIndex) throws Exception;
    }

    /** Starts {@code threads} workers behind a shared gate so they collide as hard as possible. */
    public static void run(int threads, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    task.run(idx);
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
