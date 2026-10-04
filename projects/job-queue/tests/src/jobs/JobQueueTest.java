package jobs;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class JobQueueTest {

    static final class ManualTime implements TimeSource {
        final AtomicLong now = new AtomicLong(100_000);

        public long nowMillis() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    static final RetryPolicy POLICY = new RetryPolicy(3, 1_000, 3_000);

    static Lease take(JobQueue q) {
        Optional<Lease> l = q.poll("w1", 30_000);
        assertTrue(l.isPresent(), "expected a ready job");
        return l.get();
    }

    @Test("submit then poll hands out a running lease")
    public void basicLease() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY);
        String id = q.submit("email", "to=ana", 0, t.nowMillis());
        assertEquals("J1", id);
        Lease l = take(q);
        assertEquals("J1", l.jobId());
        assertEquals("email", l.type());
        assertEquals("to=ana", l.payload());
        assertEquals(1, l.attempt());
        assertEquals(t.nowMillis() + 30_000, l.leaseUntil());
        JobView v = q.view(id);
        assertEquals(JobStatus.RUNNING, v.status());
        assertEquals(1, v.attempts());
        assertTrue(q.poll("w2", 30_000).isEmpty(), "a running job is not handed out again");
    }

    @Test("jobs scheduled in the future are not ready yet")
    public void runAt() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY);
        q.submit("report", null, 0, t.nowMillis() + 5_000);
        assertTrue(q.poll("w", 1_000).isEmpty(), "not ready");
        t.advance(4_999);
        assertTrue(q.poll("w", 1_000).isEmpty(), "1ms early");
        t.advance(1);
        Lease l = take(q);
        assertEquals("", l.payload(), "null payload stored as empty");
    }

    @Test("best job: priority, then earliest runAt, then submission order")
    public void ordering() {
        ManualTime t = new ManualTime();
        long now = t.nowMillis();
        JobQueue q = new JobQueue(t, POLICY);
        String lowOld = q.submit("x", "", 1, now - 500);
        String highLate = q.submit("x", "", 5, now - 100);
        String highEarly = q.submit("x", "", 5, now - 200);
        String highEarlyTwin = q.submit("x", "", 5, now - 200);
        assertEquals(highEarly, take(q).jobId());
        assertEquals(highEarlyTwin, take(q).jobId());
        assertEquals(highLate, take(q).jobId());
        assertEquals(lowOld, take(q).jobId());
    }

    @Test("complete marks the job succeeded")
    public void complete() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY);
        q.submit("x", "", 0, t.nowMillis());
        Lease l = take(q);
        q.complete(l.jobId(), l.token());
        assertEquals(JobStatus.SUCCEEDED, q.view(l.jobId()).status());
        assertTrue(q.poll("w", 1_000).isEmpty());
        assertThrows(IllegalStateException.class, () -> q.complete(l.jobId(), l.token()), "can't complete twice");
    }

    @Test("failures retry with exponential backoff, capped")
    public void backoff() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, new RetryPolicy(5, 1_000, 3_000));
        String id = q.submit("x", "", 0, t.nowMillis());
        long[] expected = {1_000, 2_000, 3_000, 3_000}; // capped at 3000
        for (int attempt = 1; attempt <= 4; attempt++) {
            Lease l = take(q);
            assertEquals(attempt, l.attempt(), "attempt number");
            q.fail(id, l.token(), "smtp timeout");
            JobView v = q.view(id);
            assertEquals(JobStatus.PENDING, v.status());
            assertEquals("smtp timeout", v.lastError());
            assertEquals(t.nowMillis() + expected[attempt - 1], v.nextRunAt(), "backoff after attempt " + attempt);
            t.advance(expected[attempt - 1] - 1);
            assertTrue(q.poll("w", 1_000).isEmpty(), "not ready 1ms before backoff ends");
            t.advance(1);
        }
    }

    @Test("after maxAttempts the job is dead-lettered")
    public void deadLetter() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY); // 3 attempts
        String id = q.submit("invoice", "", 0, t.nowMillis());
        q.submit("other", "", 0, t.nowMillis() + 1_000_000);
        for (int i = 0; i < 3; i++) {
            Lease l = take(q);
            q.fail(id, l.token(), i == 2 ? "final error" : null);
            t.advance(10_000);
        }
        JobView v = q.view(id);
        assertEquals(JobStatus.DEAD, v.status());
        assertEquals(3, v.attempts());
        assertEquals("final error", v.lastError());
        List<JobView> dead = q.deadLetters();
        assertEquals(1, dead.size());
        assertEquals(id, dead.get(0).id());
        assertTrue(q.poll("w", 1_000).isEmpty(), "dead jobs never run");
    }

    @Test("fail without a message records \"failed\"")
    public void defaultError() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY);
        String id = q.submit("x", "", 0, t.nowMillis());
        Lease l = take(q);
        q.fail(id, l.token(), null);
        assertEquals("failed", q.view(id).lastError());
    }

    @Test("retryDead revives a dead job with a fresh attempt budget")
    public void retryDead() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, new RetryPolicy(1, 1_000, 1_000));
        String id = q.submit("x", "", 0, t.nowMillis());
        Lease l = take(q);
        q.fail(id, l.token(), "boom");
        assertEquals(JobStatus.DEAD, q.view(id).status());
        q.retryDead(id);
        JobView v = q.view(id);
        assertEquals(JobStatus.PENDING, v.status());
        assertEquals(0, v.attempts());
        assertEquals(1, take(q).attempt(), "ready immediately");
        assertThrows(IllegalStateException.class, () -> q.retryDead(id), "only DEAD jobs");
    }

    @Test("an expired lease counts as a failed attempt and frees the job")
    public void leaseExpiry() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY);
        String id = q.submit("resize", "", 0, t.nowMillis());
        Lease first = q.poll("w1", 5_000).orElseThrow();
        long expiredAt = first.leaseUntil();
        t.advance(4_999);
        assertTrue(q.poll("w2", 5_000).isEmpty(), "lease still valid");
        t.advance(1); // lease ends now
        JobView v = q.view(id);
        assertEquals(JobStatus.PENDING, v.status());
        assertEquals("lease expired", v.lastError());
        assertEquals(expiredAt + 1_000, v.nextRunAt(), "backoff counted from when the lease ran out");
        t.advance(1_000);
        Lease second = q.poll("w2", 5_000).orElseThrow();
        assertEquals(2, second.attempt());
        assertNotEquals(first.token(), second.token(), "every lease gets a new token");
    }

    @Test("a zombie worker can't complete or fail a job that was re-leased")
    public void zombieWorker() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY);
        String id = q.submit("x", "", 0, t.nowMillis());
        Lease zombie = q.poll("w1", 1_000).orElseThrow();
        t.advance(1_000 + 1_000); // lease expired, backoff elapsed
        Lease fresh = q.poll("w2", 60_000).orElseThrow();
        assertThrows(IllegalStateException.class, () -> q.complete(id, zombie.token()), "stale token");
        assertThrows(IllegalStateException.class, () -> q.fail(id, zombie.token(), "x"), "stale token");
        assertEquals(JobStatus.RUNNING, q.view(id).status(), "zombie did not change anything");
        q.complete(id, fresh.token());
        assertEquals(JobStatus.SUCCEEDED, q.view(id).status());
    }

    @Test("completing after your own lease expired is rejected")
    public void lateComplete() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY);
        String id = q.submit("x", "", 0, t.nowMillis());
        Lease l = q.poll("w1", 1_000).orElseThrow();
        t.advance(1_000);
        assertThrows(IllegalStateException.class, () -> q.complete(id, l.token()));
        assertEquals("lease expired", q.view(id).lastError());
    }

    @Test("wrong token, unknown job, cancel rules")
    public void errors() {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY);
        String a = q.submit("x", "", 0, t.nowMillis());
        String b = q.submit("x", "", 0, t.nowMillis() + 60_000);
        Lease l = take(q);
        assertThrows(IllegalStateException.class, () -> q.complete(a, "made-up"));
        assertThrows(NoSuchElementException.class, () -> q.view("J99"));
        assertThrows(IllegalStateException.class, () -> q.cancel(a), "running job can't be cancelled");
        q.cancel(b);
        assertEquals(JobStatus.CANCELLED, q.view(b).status());
        q.complete(a, l.token());
        t.advance(60_000);
        assertTrue(q.poll("w", 1_000).isEmpty(), "cancelled job never runs");
    }

    @Test("validation")
    public void validation() {
        ManualTime t = new ManualTime();
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 10, 5));
        JobQueue q = new JobQueue(t, POLICY);
        assertThrows(IllegalArgumentException.class, () -> q.submit(" ", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> q.poll("", 1_000));
        assertThrows(IllegalArgumentException.class, () -> q.poll("w", 0));
    }

    @Test("16 workers: every job is leased once and completed once")
    public void concurrentWorkers() throws Exception {
        ManualTime t = new ManualTime();
        JobQueue q = new JobQueue(t, POLICY);
        for (int i = 0; i < 300; i++) q.submit("x", "p" + i, i % 7, t.nowMillis());
        Set<String> leased = ConcurrentHashMap.newKeySet();
        Concurrent.run(16, w -> {
            while (true) {
                Optional<Lease> l = q.poll("worker-" + w, 60_000);
                if (l.isEmpty()) break;
                assertTrue(leased.add(l.get().jobId()), "job leased twice: " + l.get().jobId());
                q.complete(l.get().jobId(), l.get().token());
            }
        });
        assertEquals(300, leased.size());
        for (int i = 1; i <= 300; i++) assertEquals(JobStatus.SUCCEEDED, q.view("J" + i).status());
    }
}
