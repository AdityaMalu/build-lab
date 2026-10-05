package jobs;

public record JobView(String id, String type, int priority, JobStatus status, int attempts, long nextRunAt,
                      String lastError) {
}
