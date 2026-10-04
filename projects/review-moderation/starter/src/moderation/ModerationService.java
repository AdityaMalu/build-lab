package moderation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public class ModerationService {

    private final Set<String> banned;
    private final Map<String, Submission> store = new HashMap<>();
    private final List<ModerationListener> listeners = new ArrayList<>();
    private int nextId = 1;
    private int failed = 0;

    public ModerationService(Set<String> bannedWords) {
        this.banned = Set.copyOf(bannedWords);
    }

    public void addListener(ModerationListener l) {
        listeners.add(l);
    }

    public Submission submit(String author, String text) {
        if (author == null || author.isBlank()) throw new IllegalArgumentException("author required");
        String id = "S" + nextId++;
        Submission s;
        if (text == null || text.isEmpty() || text.length() > 1000) {
            s = new Submission(id, author, text, Status.REJECTED, "auto", "invalid length");
        } else if (containsBanned(text)) {
            s = new Submission(id, author, text, Status.REJECTED, "auto", "banned word");
        } else {
            s = new Submission(id, author, text, Status.PENDING, null, null);
        }
        store.put(id, s);
        return s;
    }

    public Submission approve(String id, String moderator) {
        Submission s = get(id);
        Submission decided = s.decide(Status.APPROVED, moderator, null);
        notifyListeners(decided);
        store.put(id, decided);
        return decided;
    }

    public Submission reject(String id, String moderator, String reason) {
        Submission s = get(id);
        Submission decided = s.decide(Status.REJECTED, moderator, reason);
        notifyListeners(decided);
        store.put(id, decided);
        return decided;
    }

    public List<Submission> byStatus(Status status) {
        List<Submission> out = new ArrayList<>();
        for (Submission s : store.values()) if (s.status() == status) out.add(s);
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    public int failedNotifications() {
        return failed;
    }

    private Submission get(String id) {
        Submission s = store.get(id);
        if (s == null) throw new NoSuchElementException("no submission " + id);
        return s;
    }

    private boolean containsBanned(String text) {
        for (String word : banned) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private void notifyListeners(Submission s) {
        for (ModerationListener l : listeners) {
            l.onDecision(s);
        }
    }
}
