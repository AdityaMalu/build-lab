package flags;

public final class Bucketing {
    private Bucketing() {}

    /** 32-bit FNV-1a of the UTF-8 bytes of key, as an unsigned value, modulo buckets. */
    public static int bucket(String key, int buckets) {
        throw new UnsupportedOperationException("TODO");
    }
}
