import threading

from comments import CommentService
from labtest import assert_equal, assert_false, assert_is_none, assert_raises, assert_true, run_concurrently, test


class CommentServiceTest:

    @test("top-level comment gets id c1, depth 0, stripped text")
    def add_comment(self):
        s = CommentService()
        c = s.add_comment("p1", "ana", "  hello world  ")
        assert_equal("c1", c.id)
        assert_equal(1, c.seq)
        assert_equal("p1", c.post_id)
        assert_is_none(c.parent_id, "top level has no parent")
        assert_equal(0, c.depth)
        assert_equal("hello world", c.text)
        assert_false(c.deleted)

    @test("reply inherits post and increments depth")
    def reply_depth(self):
        s = CommentService()
        root = s.add_comment("p1", "ana", "root")
        r1 = s.reply(root.id, "ben", "r1")
        r2 = s.reply(r1.id, "ana", "r2")
        assert_equal("p1", r2.post_id)
        assert_equal(r1.id, r2.parent_id)
        assert_equal(2, r2.depth)

    @test("thread is nested and ordered oldest first at every level")
    def thread_shape(self):
        s = CommentService()
        a = s.add_comment("p1", "u", "A")
        b = s.add_comment("p1", "u", "B")
        s.add_comment("p2", "u", "other post")
        a1 = s.reply(a.id, "u", "A1")
        b1 = s.reply(b.id, "u", "B1")
        a2 = s.reply(a.id, "u", "A2")
        a1x = s.reply(a1.id, "u", "A1x")
        thread = s.get_thread("p1")
        assert_equal(2, len(thread), "two top-level comments on p1")
        assert_equal(["A", "B"], [n.comment.text for n in thread])
        assert_equal([a1.id, a2.id], [n.comment.id for n in thread[0].replies])
        assert_equal(a1x.id, thread[0].replies[0].replies[0].comment.id)
        assert_equal(b1.id, thread[1].replies[0].comment.id)
        assert_equal([], list(s.get_thread("nobody")), "unknown post gives empty thread")

    @test("validation errors")
    def validation(self):
        s = CommentService()
        assert_raises(ValueError, lambda: s.add_comment(None, "u", "x"))
        assert_raises(ValueError, lambda: s.add_comment("p", " ", "x"))
        assert_raises(ValueError, lambda: s.add_comment("p", "u", "   "))
        assert_raises(ValueError, lambda: s.add_comment("p", "u", None))
        assert_raises(ValueError, lambda: s.add_comment("p", "u", "x" * 501))
        ok = s.add_comment("p", "u", "  " + "x" * 500 + "  ")
        assert_equal(500, len(ok.text), "500 chars after stripping is allowed")
        assert_raises(KeyError, lambda: s.reply("c999", "u", "x"))

    @test("max depth is 3")
    def max_depth(self):
        s = CommentService()
        c = s.add_comment("p", "u", "d0")
        for d in range(1, 4):
            c = s.reply(c.id, "u", f"d{d}")
        assert_equal(3, c.depth)
        assert_raises(RuntimeError, lambda: s.reply(c.id, "u", "too deep"))

    @test("soft delete keeps the node, hides author and text")
    def soft_delete(self):
        s = CommentService()
        root = s.add_comment("p", "ana", "secret")
        s.reply(root.id, "ben", "child")
        s.delete(root.id, "ana")
        node = s.get_thread("p")[0]
        assert_true(node.comment.deleted)
        assert_equal("[deleted]", node.comment.text)
        assert_is_none(node.comment.author, "author hidden")
        assert_equal(1, len(node.replies), "replies survive")
        assert_equal(1, s.count_visible("p"))
        s.delete(root.id, "ana")  # no-op
        assert_raises(RuntimeError, lambda: s.reply(root.id, "ben", "x"))

    @test("only the author can delete")
    def delete_authorization(self):
        s = CommentService()
        c = s.add_comment("p", "ana", "mine")
        assert_raises(PermissionError, lambda: s.delete(c.id, "ben"))
        assert_raises(KeyError, lambda: s.delete("c42", "ana"))
        assert_equal(1, s.count_visible("p"))

    @test("returned thread is a snapshot")
    def snapshot(self):
        s = CommentService()
        c = s.add_comment("p", "u", "first")
        before = s.get_thread("p")
        s.add_comment("p", "u", "second")
        s.reply(c.id, "u", "reply")
        assert_equal(1, len(before), "old list must not grow")
        assert_equal(0, len(before[0].replies), "old replies must not grow")

    @test("concurrent writers get unique sequential ids")
    def concurrency(self):
        s = CommentService()
        root = s.add_comment("p", "u", "root")
        ids = set()
        lock = threading.Lock()

        def write(i):
            for k in range(100):
                c = s.add_comment("p", f"u{i}", "t") if k % 2 == 0 else s.reply(root.id, f"u{i}", "r")
                with lock:
                    ids.add(c.id)

        run_concurrently(16, write)
        assert_equal(1600, len(ids), "all ids unique")
        assert_equal({f"c{n}" for n in range(2, 1602)}, ids, "ids are c2..c1601")
        assert_equal(1601, s.count_visible("p"))
        assert_equal(800, len(s.get_thread("p")[0].replies))
