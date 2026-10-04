package ratelimiter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

public class SlidingWindowLimiter implements RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final TimeSource time;
    private final ConcurrentHashMap<String, Deque<Long>> logs = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int maxRequests, long windowMillis, TimeSource time) {
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
        if (windowMillis <= 0) throw new IllegalArgumentException("windowMillis must be positive");
        if (time == null) throw new IllegalArgumentException("time required");
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
        this.time = time;
    }

    @Override
    public boolean tryAcquire(String clientId) {
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("clientId required");
        Deque<Long> log = logs.computeIfAbsent(clientId, k -> new ArrayDeque<>());
        synchronized (log) {
            long now = time.nowMillis();
            while (!log.isEmpty() && log.peekFirst() <= now - windowMillis) {
                log.pollFirst();
            }
            if (log.size() < maxRequests) {
                log.addLast(now);
                return true;
            }
            return false;
        }
    }
}
