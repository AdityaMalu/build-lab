package reset;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class PasswordResetService {

    private record Pending(String code, Instant expiresAt) {}

    private final Clock clock;
    private final UserStore users;
    private final CodeSource codes;
    private final long ttlMinutes;
    private final int codeLength;
    private final ZoneId zone;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public PasswordResetService(Properties config, Clock clock, UserStore users, CodeSource codes) {
        this.clock = clock;
        this.users = users;
        this.codes = codes;
        this.ttlMinutes = Long.parseLong(config.getProperty("reset.ttl.minute", "15"));
        this.codeLength = Integer.parseInt(config.getProperty("reset.code.length", "6"));
        this.zone = ZoneId.of(config.getProperty("reset.timezone", "UTC"));
    }

    public String requestReset(String email) {
        if (!users.exists(email)) throw new NoSuchElementException("unknown user");
        int bound = (int) Math.pow(10, codeLength);
        String code = Integer.toString(codes.nextInt(bound));
        Instant expires = LocalDateTime.now().plusMinutes(ttlMinutes).toInstant(ZoneOffset.UTC);
        pending.put(email, new Pending(code, expires));
        return code;
    }

    public Instant expiresAt(String email) {
        Pending p = pending.get(email);
        if (p == null) throw new NoSuchElementException("no pending reset");
        return p.expiresAt();
    }

    public String describeExpiry(String email) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());
        return fmt.format(expiresAt(email));
    }

    public boolean confirmReset(String email, String code, String newPassword) {
        Pending p = pending.get(email);
        if (p == null) return false;
        if (clock.instant().isAfter(p.expiresAt())) return false;
        if (!p.code().equals(code)) return false;
        if (newPassword == null || newPassword.length() < 8) throw new IllegalArgumentException("password too short");
        users.setPasswordHash(email, newPassword);
        return true;
    }
}
