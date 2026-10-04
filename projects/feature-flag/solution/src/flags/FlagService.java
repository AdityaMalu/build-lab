package flags;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FlagService {

    /** Flags are copied into immutable snapshots, so a reader never sees a half-built flag. */
    private final Map<String, Flag> flags = new ConcurrentHashMap<>();

    public void define(Flag flag) {
        if (flag == null) throw new IllegalArgumentException("flag required");
        if (flag.key() == null || flag.key().isBlank()) throw new IllegalArgumentException("key required");
        if (flag.rolloutPercent() < 0 || flag.rolloutPercent() > 100) throw new IllegalArgumentException("rollout 0..100");
        if (flag.rules() == null) throw new IllegalArgumentException("rules required (may be empty)");
        List<Variant> variants = flag.variants() == null ? List.of() : List.copyOf(flag.variants());
        int sum = 0;
        for (Variant v : variants) {
            if (v.weight() < 0) throw new IllegalArgumentException("negative weight");
            sum += v.weight();
        }
        if (!variants.isEmpty() && sum != 100) throw new IllegalArgumentException("weights must sum to 100");
        for (Rule r : flag.rules()) {
            if (r == null || r.attribute() == null || r.variant() == null) throw new IllegalArgumentException("bad rule");
        }
        Flag snapshot = new Flag(flag.key(), flag.enabled(), flag.rolloutPercent(), List.copyOf(flag.rules()), variants);
        flags.put(snapshot.key(), snapshot);
    }

    public String evaluate(String flagKey, User user) {
        Flag flag = flagKey == null ? null : flags.get(flagKey);
        if (flag == null) throw new IllegalArgumentException("unknown flag " + flagKey);
        if (user == null || user.id() == null || user.id().isBlank()) throw new IllegalArgumentException("user required");
        if (!flag.enabled()) return "off";

        Map<String, String> attrs = user.attributes() == null ? Map.of() : user.attributes();
        for (Rule r : flag.rules()) {
            String value = attrs.get(r.attribute());
            if (value != null && value.equals(r.equalsValue())) return r.variant();
        }

        if (Bucketing.bucket(flagKey + ":" + user.id(), 100) >= flag.rolloutPercent()) return "off";
        if (flag.variants().isEmpty()) return "on";

        int b = Bucketing.bucket(flagKey + ":variant:" + user.id(), 100);
        int cumulative = 0;
        for (Variant v : flag.variants()) {
            cumulative += v.weight();
            if (b < cumulative) return v.name();
        }
        throw new IllegalStateException("weights did not cover bucket " + b);
    }
}
