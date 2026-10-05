package reset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Given. Demo-grade hashing so the exercise has no dependencies.
 * In production use a slow, salted KDF (bcrypt / scrypt / Argon2) with a per-user salt.
 */
public final class PasswordHasher {
    private static final String APP_SALT = "practice-lab:";

    private PasswordHasher() {}

    public static String hash(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((APP_SALT + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
