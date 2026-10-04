package reset;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixes: correct config key (reset.ttl.minutes) with validation; zero-padded fixed-length codes;
 * expiry from the injected Clock; describeExpiry in the configured zone; expired at now >= expiry;
 * password validated before anything else; hash stored, not the raw password; code consumed on
 * success; at most 5 wrong guesses per code; constant-time code comparison.
 */
public class PasswordResetService {

    static final int MAX_ATTEMPTS = 5;

    private static final class Pending {
        final String code;
        final Instant expiresAt;
        int failures;

        Pending(String code, Instant expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }

    private final Clock clock;
    private final UserStore users;
    private final CodeSource codes;
    private final Duration ttl;
    private final int codeLength;
    private final ZoneId zone;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public PasswordResetService(Properties config, Clock clock, UserStore users, CodeSource codes) {
        if (config == null || clock == null || users == null || codes == null) {
            throw new IllegalArgumentException("all dependencies are required");
        }
        this.clock = clock;
        this.users = users;
        this.codes = codes;
        long minutes = parseLong(config.getProperty("reset.ttl.minutes", "15"), "reset.ttl.minutes");
        if (minutes <= 0) throw new IllegalArgumentException("reset.ttl.minutes must be positive");
        this.ttl = Duration.ofMinutes(minutes);
        long len = parseLong(config.getProperty("reset.code.length", "6"), "reset.code.length");
        if (len < 4 || len > 10) throw new IllegalArgumentException("reset.code.length must be 4..10");
        this.codeLength = (int) len;
        try {
            this.zone = ZoneId.of(config.getProperty("reset.timezone", "UTC").trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid reset.timezone", e);
        }
    }

    public String requestReset(String email) {
        if (!users.exists(email)) throw new NoSuchElementException("unknown user");
        String code = generateCode();
        pending.put(email, new Pending(code, clock.instant().plus(ttl)));
        return code;
    }

    public Instant expiresAt(String email) {
        Pending p = email == null ? null : pending.get(email);
        if (p == null) throw new NoSuchElementException("no pending reset");
        return p.expiresAt;
    }

    public String describeExpiry(String email) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ENGLISH).withZone(zone);
        return fmt.format(expiresAt(email));
    }

    public boolean confirmReset(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) throw new IllegalArgumentException("password too short");
        if (email == null) return false;
        boolean[] ok = {false};
        // compute() makes check-and-consume atomic, so two racing confirmations can't both succeed
        pending.computeIfPresent(email, (k, p) -> {
            if (!clock.instant().isBefore(p.expiresAt)) return null; // expired: drop it
            if (code == null || !constantTimeEquals(p.code, code)) {
                p.failures++;
                return p.failures >= MAX_ATTEMPTS ? null : p;
            }
            ok[0] = true;
            return null; // consumed
        });
        if (ok[0]) users.setPasswordHash(email, PasswordHasher.hash(newPassword));
        return ok[0];
    }

    private String generateCode() {
        int bound = 1;
        for (int i = 0; i < Math.min(codeLength, 9); i++) bound *= 10;
        StringBuilder sb = new StringBuilder();
        if (codeLength == 10) sb.append(codes.nextInt(10)); // 10^10 overflows int: add one leading digit
        String body = Integer.toString(codes.nextInt(bound));
        sb.append("0".repeat(Math.min(codeLength, 9) - body.length())).append(body);
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static long parseLong(String raw, String key) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }
}
