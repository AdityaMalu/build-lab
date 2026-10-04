package issues;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public class IssueService {

    private record IdempotencyRecord(String title, String body, long issueId) {}

    private static final String CURSOR_PREFIX = "after:";

    private final Map<String, User> tokens;
    private final TreeMap<Long, Issue> issues = new TreeMap<>();
    /** key: userName + '\u0000' + idempotencyKey */
    private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
    private long nextId = 1;

    public IssueService(Map<String, User> tokens) {
        this.tokens = Map.copyOf(tokens);
    }

    public User authenticate(String token) {
        User u = token == null ? null : tokens.get(token);
        if (u == null) throw new ApiException(401, "invalid or missing token");
        return u;
    }

    public synchronized CreateResult create(String token, String idempotencyKey, String title, String body) {
        User user = requireWriter(token);
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ApiException(400, "Idempotency-Key required");
        String t = cleanTitle(title);
        String b = cleanBody(body);

        String key = user.name() + '\u0000' + idempotencyKey;
        IdempotencyRecord seen = idempotency.get(key);
        if (seen != null) {
            if (seen.title().equals(t) && seen.body().equals(b)) {
                return new CreateResult(issues.get(seen.issueId()), true);
            }
            throw new ApiException(409, "Idempotency-Key reused with a different payload");
        }
        Issue issue = new Issue(nextId++, t, b, user.name(), 1);
        issues.put(issue.id(), issue);
        idempotency.put(key, new IdempotencyRecord(t, b, issue.id()));
        return new CreateResult(issue, false);
    }

    public synchronized Issue get(String token, long id) {
        authenticate(token);
        return find(id);
    }

    public synchronized Issue update(String token, long id, long expectedVersion, String newTitle) {
        requireWriter(token);
        String t = cleanTitle(newTitle);
        Issue current = find(id);
        if (current.version() != expectedVersion) {
            throw new ApiException(412, "version mismatch: current is " + current.version());
        }
        Issue updated = new Issue(current.id(), t, current.body(), current.author(), current.version() + 1);
        issues.put(id, updated);
        return updated;
    }

    public synchronized Page list(String token, int limit, String cursor) {
        authenticate(token);
        if (limit < 1 || limit > 100) throw new ApiException(400, "limit must be 1..100");
        long after = cursor == null ? 0 : decodeCursor(cursor);
        NavigableMap<Long, Issue> tail = issues.tailMap(after, false);
        List<Issue> items = new ArrayList<>(limit);
        for (Issue i : tail.values()) {
            if (items.size() == limit) break;
            items.add(i);
        }
        String next = null;
        if (!items.isEmpty()) {
            long last = items.get(items.size() - 1).id();
            if (issues.higherKey(last) != null) next = encodeCursor(last);
        }
        return new Page(List.copyOf(items), next);
    }

    private User requireWriter(String token) {
        User u = authenticate(token);
        if (u.role() != Role.WRITER) throw new ApiException(403, "writer role required");
        return u;
    }

    private Issue find(long id) {
        Issue i = issues.get(id);
        if (i == null) throw new ApiException(404, "issue " + id + " not found");
        return i;
    }

    private static String cleanTitle(String title) {
        if (title == null) throw new ApiException(400, "title required");
        String t = title.trim();
        if (t.isEmpty() || t.length() > 200) throw new ApiException(400, "title must be 1..200 characters");
        return t;
    }

    private static String cleanBody(String body) {
        String b = Objects.requireNonNullElse(body, "");
        if (b.length() > 5000) throw new ApiException(400, "body too long");
        return b;
    }

    private static String encodeCursor(long lastId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((CURSOR_PREFIX + lastId).getBytes(StandardCharsets.UTF_8));
    }

    private static long decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!raw.startsWith(CURSOR_PREFIX)) throw new IllegalArgumentException();
            long id = Long.parseLong(raw.substring(CURSOR_PREFIX.length()));
            if (id < 0) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException e) { // includes NumberFormatException
            throw new ApiException(400, "invalid cursor");
        }
    }
}
