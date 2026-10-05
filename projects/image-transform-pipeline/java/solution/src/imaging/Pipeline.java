package imaging;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

public class Pipeline {

    private final int workers;
    private final long budget;

    public Pipeline(int workers, long memoryBudgetBytes) {
        if (workers < 1) throw new IllegalArgumentException("workers must be >= 1");
        if (memoryBudgetBytes < 1 || memoryBudgetBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("budget must be 1..Integer.MAX_VALUE");
        }
        this.workers = workers;
        this.budget = memoryBudgetBytes;
    }

    public List<Result> process(List<Image> inputs, List<Transform> chain) throws InterruptedException {
        // fair = FIFO: the submitting thread acquires in input order, so nobody jumps the queue
        Semaphore memory = new Semaphore((int) budget, true);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<Result>> futures = new ArrayList<>(inputs.size());
        try {
            for (Image img : inputs) {
                long size = img.sizeBytes();
                if (size > budget) {
                    futures.add(CompletableFuture.completedFuture(
                            Result.failure(img.name(), "image of " + size + " bytes exceeds memory budget")));
                    continue;
                }
                int permits = (int) size;
                memory.acquire(permits);
                try {
                    futures.add(pool.submit(() -> {
                        try {
                            Image current = img;
                            for (Transform t : chain) current = t.apply(current);
                            return Result.success(current);
                        } catch (RuntimeException | Error e) {
                            return Result.failure(img.name(), String.valueOf(e.getMessage()));
                        } finally {
                            memory.release(permits);
                        }
                    }));
                } catch (RuntimeException rejected) {
                    memory.release(permits);
                    throw rejected;
                }
            }
            List<Result> results = new ArrayList<>(inputs.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    results.add(Result.failure(inputs.get(i).name(), String.valueOf(e.getCause())));
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
