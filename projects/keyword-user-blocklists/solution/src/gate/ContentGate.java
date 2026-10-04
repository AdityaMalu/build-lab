package gate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ContentGate {

    public static final int STRIKE_LIMIT = 3;
    public static final long STRIKE_WINDOW_MS = 60 * 60 * 1000L;
    public static final long AUTO_BLOCK_MS = 24 * 60 * 60 * 1000L;

    private static final class UserState {
        long blockedUntil = Long.MIN_VALUE; // Long.MAX_VALUE = permanent
        final Deque<Long> strikes = new ArrayDeque<>();
    }

    private final TimeSource time;
    /** Each keyword stored as its normalized token list joined by a single space. */
    private final Set<String> keywords = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, UserState> users = new ConcurrentHashMap<>();

    public ContentGate(TimeSource time) {
        if (time == null) throw new IllegalArgumentException("time required");
        this.time = time;
    }

    public void addKeyword(String phrase) {
        keywords.add(normalize(phrase));
    }

    public void removeKeyword(String phrase) {
        keywords.remove(normalize(phrase));
    }

    public void blockUser(String userId, long durationMillis) {
        UserState s = state(userId);
        synchronized (s) {
            long now = time.nowMillis();
            s.blockedUntil = durationMillis <= 0 ? Long.MAX_VALUE : saturatingAdd(now, durationMillis);
        }
    }

    public void unblockUser(String userId) {
        UserState s = state(userId);
        synchronized (s) {
            s.blockedUntil = Long.MIN_VALUE;
            s.strikes.clear();
        }
    }

    public boolean isBlocked(String userId) {
        UserState s = state(userId);
        synchronized (s) {
            return time.nowMillis() < s.blockedUntil;
        }
    }

    public Decision submit(String userId, String text) {
        UserState s = state(userId);
        // matching is pure and doesn't need the user lock
        boolean violates = matches(tokens(text == null ? "" : text));
        synchronized (s) {
            long now = time.nowMillis();
            if (now < s.blockedUntil) return Decision.BLOCKED_USER;
            if (!violates) return Decision.ACCEPTED;
            prune(s, now);
            s.strikes.addLast(now);
            if (s.strikes.size() >= STRIKE_LIMIT) {
                s.blockedUntil = saturatingAdd(now, AUTO_BLOCK_MS);
                s.strikes.clear();
            }
            return Decision.REJECTED_KEYWORD;
        }
    }

    public int activeStrikes(String userId) {
        UserState s = state(userId);
        synchronized (s) {
            prune(s, time.nowMillis());
            return s.strikes.size();
        }
    }

    private boolean matches(List<String> textTokens) {
        if (textTokens.isEmpty()) return false;
        String joined = " " + String.join(" ", textTokens) + " ";
        for (String k : keywords) {
            if (joined.contains(" " + k + " ")) return true;
        }
        return false;
    }

    private static void prune(UserState s, long now) {
        while (!s.strikes.isEmpty() && s.strikes.peekFirst() + STRIKE_WINDOW_MS <= now) s.strikes.pollFirst();
    }

    private UserState state(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId required");
        return users.computeIfAbsent(userId, k -> new UserState());
    }

    private static String normalize(String phrase) {
        if (phrase == null) throw new IllegalArgumentException("phrase required");
        List<String> t = tokens(phrase);
        if (t.isEmpty()) throw new IllegalArgumentException("phrase has no words");
        return String.join(" ", t);
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static long saturatingAdd(long a, long b) {
        long r = a + b;
        return (r < a) ? Long.MAX_VALUE : r;
    }
}
