package jobs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

public class JobQueue {

    private static final class Job {
        final String id;
        final long seq;
        final String type;
        final String payload;
        final int priority;
        JobStatus status = JobStatus.PENDING;
        int attempts;
        long runAt;
        String lastError;
        String leaseToken;
        long leaseUntil;

        Job(String id, long seq, String type, String payload, int priority, long runAt) {
            this.id = id;
            this.seq = seq;
            this.type = type;
            this.payload = payload;
            this.priority = priority;
            this.runAt = runAt;
        }

        JobView view() {
            return new JobView(id, type, priority, status, attempts, runAt, lastError);
        }
    }

    private final TimeSource time;
    private final RetryPolicy policy;
    private final Map<String, Job> jobs = new LinkedHashMap<>(); // submission order
    private long nextSeq = 1;
    private long nextToken = 1;

    public JobQueue(TimeSource time, RetryPolicy policy) {
        if (time == null || policy == null) throw new IllegalArgumentException("time and policy required");
        this.time = time;
        this.policy = policy;
    }

    public synchronized String submit(String type, String payload, int priority, long runAtMillis) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type required");
        long seq = nextSeq++;
        Job j = new Job("J" + seq, seq, type, payload == null ? "" : payload, priority, runAtMillis);
        jobs.put(j.id, j);
        return j.id;
    }

    public synchronized Optional<Lease> poll(String workerId, long leaseMillis) {
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
        if (leaseMillis <= 0) throw new IllegalArgumentException("leaseMillis must be > 0");
        long now = time.nowMillis();
        reclaimExpired(now);
        Job best = null;
        for (Job j : jobs.values()) {
            if (j.status == JobStatus.PENDING && j.runAt <= now && (best == null || better(j, best))) best = j;
        }
        if (best == null) return Optional.empty();
        best.status = JobStatus.RUNNING;
        best.attempts++;
        best.leaseToken = best.id + "-" + (nextToken++);
        best.leaseUntil = now + leaseMillis;
        return Optional.of(new Lease(best.id, best.leaseToken, best.type, best.payload, best.attempts, best.leaseUntil));
    }

    public synchronized void complete(String jobId, String token) {
        Job j = find(jobId);
        reclaimExpired(time.nowMillis());
        requireLease(j, token);
        j.status = JobStatus.SUCCEEDED;
        j.leaseToken = null;
    }

    public synchronized void fail(String jobId, String token, String error) {
        Job j = find(jobId);
        long now = time.nowMillis();
        reclaimExpired(now);
        requireLease(j, token);
        failAttempt(j, now, error == null ? "failed" : error);
    }

    public synchronized JobView view(String jobId) {
        reclaimExpired(time.nowMillis());
        return find(jobId).view();
    }

    public synchronized List<JobView> deadLetters() {
        reclaimExpired(time.nowMillis());
        List<JobView> out = new ArrayList<>();
        for (Job j : jobs.values()) if (j.status == JobStatus.DEAD) out.add(j.view());
        return out;
    }

    public synchronized void retryDead(String jobId) {
        Job j = find(jobId);
        if (j.status != JobStatus.DEAD) throw new IllegalStateException("job is " + j.status + ", not DEAD");
        j.status = JobStatus.PENDING;
        j.attempts = 0;
        j.runAt = time.nowMillis();
    }

    public synchronized void cancel(String jobId) {
        Job j = find(jobId);
        if (j.status != JobStatus.PENDING) throw new IllegalStateException("only PENDING jobs can be cancelled");
        j.status = JobStatus.CANCELLED;
    }

    // ------------------------------------------------------------ internals

    private static boolean better(Job a, Job b) {
        if (a.priority != b.priority) return a.priority > b.priority;
        if (a.runAt != b.runAt) return a.runAt < b.runAt;
        return a.seq < b.seq;
    }

    /** A lease that ran out is a failed attempt that happened at leaseUntil. */
    private void reclaimExpired(long now) {
        for (Job j : jobs.values()) {
            if (j.status == JobStatus.RUNNING && now >= j.leaseUntil) failAttempt(j, j.leaseUntil, "lease expired");
        }
    }

    private void failAttempt(Job j, long failedAt, String error) {
        j.lastError = error;
        j.leaseToken = null;
        if (j.attempts >= policy.maxAttempts()) {
            j.status = JobStatus.DEAD;
        } else {
            j.status = JobStatus.PENDING;
            j.runAt = failedAt + backoff(j.attempts);
        }
    }

    /** min(max, base * 2^(n-1)) without overflow. */
    private long backoff(int attempt) {
        long b = policy.baseBackoffMillis();
        for (int i = 1; i < attempt && b < policy.maxBackoffMillis(); i++) {
            b = b > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : b * 2;
        }
        return Math.min(b, policy.maxBackoffMillis());
    }

    private void requireLease(Job j, String token) {
        if (j.status != JobStatus.RUNNING || token == null || !token.equals(j.leaseToken)) {
            throw new IllegalStateException("lease not held for " + j.id + " (job is " + j.status + ")");
        }
    }

    private Job find(String jobId) {
        Job j = jobId == null ? null : jobs.get(jobId);
        if (j == null) throw new NoSuchElementException("no job " + jobId);
        return j;
    }
}
