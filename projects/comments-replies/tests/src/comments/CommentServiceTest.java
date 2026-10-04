package comments;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import testkit.Concurrent;
import testkit.Test;

import static testkit.Assert.*;

public class CommentServiceTest {

    @Test("top-level comment gets id c1, depth 0, trimmed text")
    public void addComment() {
        CommentService s = new CommentService();
        Comment c = s.addComment("p1", "ana", "  hello world  ");
        assertEquals("c1", c.id());
        assertEquals(1L, (Object) c.seq());
        assertEquals("p1", c.postId());
        assertNull(c.parentId(), "top level has no parent");
        assertEquals(0, c.depth());
        assertEquals("hello world", c.text());
        assertFalse(c.deleted());
    }

    @Test("reply inherits post and increments depth")
    public void replyDepth() {
        CommentService s = new CommentService();
        Comment root = s.addComment("p1", "ana", "root");
        Comment r1 = s.reply(root.id(), "ben", "r1");
        Comment r2 = s.reply(r1.id(), "ana", "r2");
        assertEquals("p1", r2.postId());
        assertEquals(r1.id(), r2.parentId());
        assertEquals(2, r2.depth());
    }

    @Test("thread is nested and ordered oldest first at every level")
    public void threadShape() {
        CommentService s = new CommentService();
        Comment a = s.addComment("p1", "u", "A");
        Comment b = s.addComment("p1", "u", "B");
        s.addComment("p2", "u", "other post");
        Comment a1 = s.reply(a.id(), "u", "A1");
        Comment b1 = s.reply(b.id(), "u", "B1");
        Comment a2 = s.reply(a.id(), "u", "A2");
        Comment a1x = s.reply(a1.id(), "u", "A1x");

        List<CommentNode> thread = s.getThread("p1");
        assertEquals(2, thread.size(), "two top-level comments on p1");
        assertEquals("A", thread.get(0).comment().text());
        assertEquals("B", thread.get(1).comment().text());
        List<CommentNode> aReplies = thread.get(0).replies();
        assertEquals(2, aReplies.size());
        assertEquals(a1.id(), aReplies.get(0).comment().id());
        assertEquals(a2.id(), aReplies.get(1).comment().id());
        assertEquals(a1x.id(), aReplies.get(0).replies().get(0).comment().id());
        assertEquals(b1.id(), thread.get(1).replies().get(0).comment().id());
        assertTrue(s.getThread("nobody").isEmpty(), "unknown post gives empty thread");
    }

    @Test("validation errors")
    public void validation() {
        CommentService s = new CommentService();
        assertThrows(IllegalArgumentException.class, () -> s.addComment(null, "u", "x"));
        assertThrows(IllegalArgumentException.class, () -> s.addComment("p", " ", "x"));
        assertThrows(IllegalArgumentException.class, () -> s.addComment("p", "u", "   "));
        assertThrows(IllegalArgumentException.class, () -> s.addComment("p", "u", null));
        assertThrows(IllegalArgumentException.class, () -> s.addComment("p", "u", "x".repeat(501)));
        Comment ok = s.addComment("p", "u", "  " + "x".repeat(500) + "  ");
        assertEquals(500, ok.text().length(), "500 chars after trimming is allowed");
        assertThrows(NoSuchElementException.class, () -> s.reply("c999", "u", "x"));
    }

    @Test("max depth is 3")
    public void maxDepth() {
        CommentService s = new CommentService();
        Comment c = s.addComment("p", "u", "d0");
        for (int d = 1; d <= 3; d++) c = s.reply(c.id(), "u", "d" + d);
        assertEquals(3, c.depth());
        final String deepest = c.id();
        assertThrows(IllegalStateException.class, () -> s.reply(deepest, "u", "too deep"));
    }

    @Test("soft delete keeps the node, hides author and text")
    public void softDelete() {
        CommentService s = new CommentService();
        Comment root = s.addComment("p", "ana", "secret");
        s.reply(root.id(), "ben", "child");
        s.delete(root.id(), "ana");
        CommentNode node = s.getThread("p").get(0);
        assertTrue(node.comment().deleted());
        assertEquals("[deleted]", node.comment().text());
        assertNull(node.comment().author(), "author hidden");
        assertEquals(1, node.replies().size(), "replies survive");
        assertEquals(1, s.countVisible("p"));
        s.delete(root.id(), "ana"); // second delete is a no-op
        assertThrows(IllegalStateException.class, () -> s.reply(root.id(), "ben", "x"));
    }

    @Test("only the author can delete")
    public void deleteAuthorization() {
        CommentService s = new CommentService();
        Comment c = s.addComment("p", "ana", "mine");
        assertThrows(SecurityException.class, () -> s.delete(c.id(), "ben"));
        assertThrows(NoSuchElementException.class, () -> s.delete("c42", "ana"));
        assertEquals(1, s.countVisible("p"));
    }

    @Test("returned thread is a snapshot")
    public void snapshot() {
        CommentService s = new CommentService();
        Comment c = s.addComment("p", "u", "first");
        List<CommentNode> before = s.getThread("p");
        s.addComment("p", "u", "second");
        s.reply(c.id(), "u", "reply");
        assertEquals(1, before.size(), "old list must not grow");
        assertEquals(0, before.get(0).replies().size(), "old replies list must not grow");
    }

    @Test("concurrent writers get unique sequential ids")
    public void concurrency() throws Exception {
        CommentService s = new CommentService();
        Comment root = s.addComment("p", "u", "root");
        Set<String> ids = ConcurrentHashMap.newKeySet();
        Concurrent.run(16, i -> {
            for (int k = 0; k < 100; k++) {
                Comment c = (k % 2 == 0) ? s.addComment("p", "u" + i, "t") : s.reply(root.id(), "u" + i, "r");
                ids.add(c.id());
            }
        });
        assertEquals(1600, ids.size(), "all ids unique");
        assertEquals(1601, s.countVisible("p"));
        Set<String> expected = new HashSet<>();
        for (int n = 2; n <= 1601; n++) expected.add("c" + n);
        assertEquals(expected, ids, "ids are c2..c1601");
        assertEquals(800, s.getThread("p").get(0).replies().size());
    }
}
