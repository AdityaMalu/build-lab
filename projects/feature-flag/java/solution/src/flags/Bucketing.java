package flags;

import java.nio.charset.StandardCharsets;

public final class Bucketing {
    private Bucketing() {}

    /** 32-bit FNV-1a of the UTF-8 bytes of key, as an unsigned value, modulo buckets. */
    public static int bucket(String key, int buckets) {
        if (key == null) throw new IllegalArgumentException("key required");
        if (buckets <= 0) throw new IllegalArgumentException("buckets must be positive");
        int hash = 0x811C9DC5;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        return (int) (Integer.toUnsignedLong(hash) % buckets);
    }
}
