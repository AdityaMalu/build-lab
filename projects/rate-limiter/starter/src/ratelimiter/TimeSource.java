package ratelimiter;

/** Injected clock so tests can control time. */
public interface TimeSource {
    long nowMillis();

    TimeSource SYSTEM = System::currentTimeMillis;
}
