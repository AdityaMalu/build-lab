package ratelimiter;

public interface RateLimiter {
    /** @return true if the request from this client is allowed right now. */
    boolean tryAcquire(String clientId);
}
