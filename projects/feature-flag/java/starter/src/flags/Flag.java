package flags;

import java.util.List;

public record Flag(String key, boolean enabled, int rolloutPercent, List<Rule> rules, List<Variant> variants) {
}
