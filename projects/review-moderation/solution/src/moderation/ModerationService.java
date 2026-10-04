package moderation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class ModerationService {

    private final Set<String> banned;
    private final Map<String, Submission> store = new ConcurrentHashMap<>();
    private final List<ModerationListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicInteger failed = new AtomicInteger();

    public ModerationService(Set<String> bannedWords) {
        this.banned = bannedWords.stream().map(w -> w.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public void addListener(ModerationListener l) {
        if (l == null) throw new IllegalArgumentException("listener required");
        listeners.add(l);
    }

    public Submission submit(String author, String text) {
        if (author == null || author.isBlank()) throw new IllegalArgumentException("author required");
        String id = "S" + nextId.getAndIncrement();
        String body = text == null ? "" : text.trim();
        Submission s;
        if (body.isEmpty() || body.length() > 1000) {
            s = new Submission(id, author, body, Status.REJECTED, "auto", "invalid length");
        } else if (containsBanned(body)) {
            s = new Submission(id, author, body, Status.REJECTED, "auto", "banned word");
        } else {
            s = new Submission(id, author, body, Status.PENDING, null, null);
        }
        store.put(id, s);
        return s;
    }

    public Submission approve(String id, String moderator) {
        return decide(id, Status.APPROVED, moderator, null);
    }

    public Submission reject(String id, String moderator, String reason) {
        return decide(id, Status.REJECTED, moderator, reason);
    }

    public List<Submission> byStatus(Status status) {
        List<Submission> out = new ArrayList<>();
        for (Submission s : store.values()) if (s.status() == status) out.add(s);
        out.sort(Comparator.comparingLong(s -> Long.parseLong(s.id().substring(1))));
        return out;
    }

    public int failedNotifications() {
        return failed.get();
    }

    private Submission decide(String id, Status target, String moderator, String reason) {
        Submission[] result = new Submission[1];
        // compute() is atomic per key: exactly one of two racing moderators wins
        store.compute(id, (k, current) -> {
            if (current == null) throw new NoSuchElementException("no submission " + id);
            if (current.status() != Status.PENDING) throw new IllegalStateException("already " + current.status());
            result[0] = current.decide(target, moderator, reason);
            return result[0];
        });
        notifyListeners(result[0]); // after the decision is saved, outside the map lock
        return result[0];
    }

    private boolean containsBanned(String text) {
        StringBuilder word = new StringBuilder();
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else if (word.length() > 0) {
                if (banned.contains(word.toString())) return true;
                word.setLength(0);
            }
        }
        return false;
    }

    private void notifyListeners(Submission s) {
        for (ModerationListener l : listeners) {
            try {
                l.onDecision(s);
            } catch (RuntimeException e) {
                failed.incrementAndGet();
            }
        }
    }
}
