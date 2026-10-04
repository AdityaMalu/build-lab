package jobs;

/** A job handed to one worker. Only the holder of {@code token} may complete or fail it. */
public record Lease(String jobId, String token, String type, String payload, int attempt, long leaseUntil) {
}
