package jobs;

/** How many times a job may run, and the exponential backoff between attempts. */
public record RetryPolicy(int maxAttempts, long baseBackoffMillis, long maxBackoffMillis) {

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (baseBackoffMillis <= 0) throw new IllegalArgumentException("baseBackoffMillis must be > 0");
        if (maxBackoffMillis < baseBackoffMillis) throw new IllegalArgumentException("maxBackoffMillis < baseBackoffMillis");
    }
}
