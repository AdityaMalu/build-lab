package jobs;

import java.util.List;
import java.util.Optional;

public class JobQueue {

    public JobQueue(TimeSource time, RetryPolicy policy) {
        // TODO
    }

    public String submit(String type, String payload, int priority, long runAtMillis) {
        throw new UnsupportedOperationException("TODO");
    }

    public Optional<Lease> poll(String workerId, long leaseMillis) {
        // TODO process expired leases first, then lease the best ready job
        throw new UnsupportedOperationException("TODO");
    }

    public void complete(String jobId, String token) {
        throw new UnsupportedOperationException("TODO");
    }

    public void fail(String jobId, String token, String error) {
        throw new UnsupportedOperationException("TODO");
    }

    public JobView view(String jobId) {
        throw new UnsupportedOperationException("TODO");
    }

    public List<JobView> deadLetters() {
        throw new UnsupportedOperationException("TODO");
    }

    public void retryDead(String jobId) {
        throw new UnsupportedOperationException("TODO");
    }

    public void cancel(String jobId) {
        throw new UnsupportedOperationException("TODO");
    }
}
