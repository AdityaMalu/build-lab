package shortener;

public interface TimeSource {
    long nowMillis();

    TimeSource SYSTEM = System::currentTimeMillis;
}
