package ratelimiter;

public class SlidingWindowLimiter implements RateLimiter {

    public SlidingWindowLimiter(int maxRequests, long windowMillis, TimeSource time) {
        // TODO validate arguments and set up per-client state
    }

    @Override
    public boolean tryAcquire(String clientId) {
        // TODO drop timestamps that fell out of the window, then decide
        throw new UnsupportedOperationException("TODO");
    }
}
