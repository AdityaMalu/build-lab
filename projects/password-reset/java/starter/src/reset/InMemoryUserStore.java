package reset;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryUserStore implements UserStore {
    private final Map<String, String> hashes = new ConcurrentHashMap<>();

    public InMemoryUserStore add(String email, String initialHash) {
        hashes.put(email, initialHash);
        return this;
    }

    @Override
    public boolean exists(String email) {
        return email != null && hashes.containsKey(email);
    }

    @Override
    public void setPasswordHash(String email, String hash) {
        if (!exists(email)) throw new IllegalArgumentException("no user " + email);
        hashes.put(email, hash);
    }

    @Override
    public String passwordHash(String email) {
        return hashes.get(email);
    }
}
