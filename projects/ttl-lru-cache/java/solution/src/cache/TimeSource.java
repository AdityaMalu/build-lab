package cache;

public interface TimeSource {
    long nowMillis();

    TimeSource SYSTEM = System::currentTimeMillis;
}
