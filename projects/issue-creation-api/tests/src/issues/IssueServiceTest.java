package issues;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class IssueServiceTest {

    static final String W = "tok-writer";
    static final String W2 = "tok-writer2";
    static final String R = "tok-reader";

    static IssueService svc() {
        return new IssueService(Map.of(
                W, new User("wendy", Role.WRITER),
                W2, new User("will", Role.WRITER),
                R, new User("rita", Role.READER)));
    }

    static int status(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e.status();
        }
        return 0;
    }

    @Test("create assigns ids, version 1, author, trims title, null body becomes empty")
    public void create() {
        IssueService s = svc();
        CreateResult r = s.create(W, "k1", "  Crash on login  ", null);
        assertFalse(r.replayed());
        assertEquals(new Issue(1, "Crash on login", "", "wendy", 1), r.issue());
        assertEquals(2L, (Object) s.create(W, "k2", "Second", "details").issue().id());
    }

    @Test("auth then role then validation: 401 / 403 / 400")
    public void errorOrder() {
        IssueService s = svc();
        assertEquals(401, status(() -> s.create(null, "k", "t", "b")));
        assertEquals(401, status(() -> s.create("bogus", "k", "", "b")), "401 wins over validation");
        assertEquals(403, status(() -> s.create(R, "k", "", "b")), "403 wins over validation");
        assertEquals(400, status(() -> s.create(W, " ", "t", "b")), "idempotency key required");
        assertEquals(400, status(() -> s.create(W, "k", "   ", "b")));
        assertEquals(400, status(() -> s.create(W, "k", "x".repeat(201), "b")));
        assertEquals(400, status(() -> s.create(W, "k", "t", "x".repeat(5001))));
        assertEquals(0, status(() -> s.create(W, "k", "x".repeat(200), "x".repeat(5000))), "limits are inclusive");
    }

    @Test("same key + same payload replays the original issue")
    public void replay() {
        IssueService s = svc();
        Issue first = s.create(W, "retry-1", "Bug", "body").issue();
        CreateResult again = s.create(W, "retry-1", " Bug ", "body");
        assertTrue(again.replayed(), "should be marked as replay");
        assertEquals(first, again.issue());
        assertEquals(1, s.list(W, 100, null).items().size(), "no duplicate created");
    }

    @Test("same key + different payload is a 409 conflict")
    public void conflict() {
        IssueService s = svc();
        s.create(W, "k", "Bug", "body");
        assertEquals(409, status(() -> s.create(W, "k", "Other", "body")));
        assertEquals(409, status(() -> s.create(W, "k", "Bug", "different body")));
    }

    @Test("idempotency keys are scoped per user")
    public void scopedKeys() {
        IssueService s = svc();
        Issue a = s.create(W, "same-key", "Bug", "b").issue();
        CreateResult b = s.create(W2, "same-key", "Bug", "b");
        assertFalse(b.replayed(), "another user's key must not replay");
        assertNotEquals(a.id(), b.issue().id(), "a new issue for the second user");
    }

    @Test("concurrent identical retries create exactly one issue")
    public void concurrentRetries() throws Exception {
        IssueService s = svc();
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        Concurrent.run(16, i -> {
            for (int k = 0; k < 20; k++) ids.add(s.create(W, "flaky-network", "Same", "payload").issue().id());
        });
        assertEquals(Set.of(1L), ids);
    }

    @Test("get: any role, 404 for unknown")
    public void get() {
        IssueService s = svc();
        s.create(W, "k", "t", "b");
        assertEquals("t", s.get(R, 1).title());
        assertEquals(404, status(() -> s.get(R, 99)));
        assertEquals(401, status(() -> s.get("nope", 1)));
    }

    @Test("optimistic concurrency: stale version gets 412")
    public void optimistic() {
        IssueService s = svc();
        s.create(W, "k", "v1 title", "b");
        Issue v2 = s.update(W, 1, 1, "v2 title");
        assertEquals(2L, (Object) v2.version());
        assertEquals("v2 title", v2.title());
        assertEquals("b", v2.body(), "body untouched");
        assertEquals(412, status(() -> s.update(W2, 1, 1, "stale edit")));
        assertEquals("v2 title", s.get(R, 1).title(), "stale edit not applied");
        assertEquals(403, status(() -> s.update(R, 1, 2, "reader edit")));
        assertEquals(404, status(() -> s.update(W, 42, 1, "x")));
        assertEquals(400, status(() -> s.update(W, 1, 2, " ")));
    }

    @Test("concurrent updates with the same version: exactly one wins")
    public void concurrentUpdates() throws Exception {
        IssueService s = svc();
        s.create(W, "k", "start", "b");
        Set<String> winners = ConcurrentHashMap.newKeySet();
        Concurrent.run(10, i -> {
            try {
                s.update(W, 1, 1, "edit-" + i);
                winners.add("edit-" + i);
            } catch (ApiException e) {
                assertEquals(412, e.status());
            }
        });
        assertEquals(1, winners.size());
        assertEquals(2L, (Object) s.get(R, 1).version());
    }

    @Test("pagination walks every issue exactly once")
    public void pagination() {
        IssueService s = svc();
        for (int i = 1; i <= 23; i++) s.create(W, "k" + i, "issue " + i, "");
        Set<Long> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            Page p = s.list(R, 5, cursor);
            pages++;
            for (Issue i : p.items()) assertTrue(seen.add(i.id()), "duplicate " + i.id());
            cursor = p.nextCursor();
        } while (cursor != null);
        assertEquals(23, seen.size());
        assertEquals(5, pages, "5+5+5+5+3");
    }

    @Test("exact final page has no next cursor; empty list works")
    public void pageEdges() {
        IssueService s = svc();
        Page empty = s.list(R, 10, null);
        assertTrue(empty.items().isEmpty());
        assertNull(empty.nextCursor(), "no cursor on empty list");
        for (int i = 1; i <= 4; i++) s.create(W, "k" + i, "t" + i, "");
        Page first = s.list(R, 2, null);
        Page second = s.list(R, 2, first.nextCursor());
        assertEquals(List.of(3L, 4L), List.of(second.items().get(0).id(), second.items().get(1).id()));
        assertNull(second.nextCursor(), "nothing after the last page");
    }

    @Test("pagination is stable while new issues are inserted")
    public void stableCursor() {
        IssueService s = svc();
        for (int i = 1; i <= 6; i++) s.create(W, "k" + i, "t" + i, "");
        Page p1 = s.list(R, 3, null);
        for (int i = 7; i <= 8; i++) s.create(W, "k" + i, "t" + i, "");
        Page p2 = s.list(R, 3, p1.nextCursor());
        assertEquals(4L, (Object) p2.items().get(0).id(), "continues after the last seen id");
        assertNotNull(p2.nextCursor(), "issues 7 and 8 are still ahead");
    }

    @Test("bad limit and garbage cursors are 400")
    public void badPaging() {
        IssueService s = svc();
        assertEquals(400, status(() -> s.list(R, 0, null)));
        assertEquals(400, status(() -> s.list(R, 101, null)));
        assertEquals(400, status(() -> s.list(R, 10, "!!not-a-cursor!!")));
        assertEquals(400, status(() -> s.list(R, 10, "aGVsbG8"))); // base64 of "hello"
    }
}
