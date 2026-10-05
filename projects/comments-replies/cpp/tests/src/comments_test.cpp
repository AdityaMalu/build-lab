#include <mutex>
#include <set>

#include "comments.hpp"
#include "labtest.hpp"

LAB_TEST(CommentServiceTest, addComment, "top-level comment gets id c1, depth 0, trimmed text") {
    CommentService s;
    Comment c = s.addComment("p1", "ana", "  hello world  ");
    ASSERT_EQ(std::string("c1"), c.id, "id");
    ASSERT_EQ(1LL, c.seq, "seq");
    ASSERT_EQ(std::string("p1"), c.postId, "post");
    ASSERT_FALSE(c.parentId.has_value(), "top level has no parent");
    ASSERT_EQ(0, c.depth, "depth");
    ASSERT_EQ(std::string("hello world"), c.text, "text must be trimmed");
    ASSERT_FALSE(c.deleted, "not deleted");
}

LAB_TEST(CommentServiceTest, replyDepth, "reply inherits post and increments depth") {
    CommentService s;
    Comment root = s.addComment("p1", "ana", "root");
    Comment r1 = s.reply(root.id, "ben", "r1");
    Comment r2 = s.reply(r1.id, "ana", "r2");
    ASSERT_EQ(std::string("p1"), r2.postId, "post");
    ASSERT_EQ(std::optional<std::string>(r1.id), r2.parentId, "parent");
    ASSERT_EQ(2, r2.depth, "depth");
}

LAB_TEST(CommentServiceTest, threadShape, "thread is nested and ordered oldest first at every level") {
    CommentService s;
    Comment a = s.addComment("p1", "u", "A");
    Comment b = s.addComment("p1", "u", "B");
    s.addComment("p2", "u", "other post");
    Comment a1 = s.reply(a.id, "u", "A1");
    Comment b1 = s.reply(b.id, "u", "B1");
    Comment a2 = s.reply(a.id, "u", "A2");
    Comment a1x = s.reply(a1.id, "u", "A1x");
    auto th = s.getThread("p1");
    ASSERT_EQ(size_t{2}, th.size(), "two top-level comments on p1");
    ASSERT_EQ(std::string("A"), th[0].comment.text, "first");
    ASSERT_EQ(std::string("B"), th[1].comment.text, "second");
    ASSERT_EQ(size_t{2}, th[0].replies.size(), "A has two replies");
    ASSERT_EQ(a1.id, th[0].replies[0].comment.id, "A1 first");
    ASSERT_EQ(a2.id, th[0].replies[1].comment.id, "A2 second");
    ASSERT_EQ(a1x.id, th[0].replies[0].replies[0].comment.id, "nested");
    ASSERT_EQ(b1.id, th[1].replies[0].comment.id, "B1");
    ASSERT_TRUE(s.getThread("nobody").empty(), "unknown post gives empty thread");
}

LAB_TEST(CommentServiceTest, validation, "validation errors") {
    CommentService s;
    ASSERT_THROWS(std::invalid_argument, s.addComment("", "u", "x"), "blank post");
    ASSERT_THROWS(std::invalid_argument, s.addComment("p", " ", "x"), "blank author");
    ASSERT_THROWS(std::invalid_argument, s.addComment("p", "u", "   "), "blank text");
    ASSERT_THROWS(std::invalid_argument, s.addComment("p", "u", std::string(501, 'x')), "too long");
    Comment ok = s.addComment("p", "u", "  " + std::string(500, 'x') + "  ");
    ASSERT_EQ(size_t{500}, ok.text.size(), "500 chars after trimming is allowed");
    ASSERT_THROWS(std::out_of_range, s.reply("c999", "u", "x"), "unknown parent");
}

LAB_TEST(CommentServiceTest, maxDepth, "max depth is 3") {
    CommentService s;
    Comment c = s.addComment("p", "u", "d0");
    for (int d = 1; d <= 3; d++) c = s.reply(c.id, "u", "d" + std::to_string(d));
    ASSERT_EQ(3, c.depth, "depth");
    ASSERT_THROWS(InvalidState, s.reply(c.id, "u", "too deep"), "depth 4");
}

LAB_TEST(CommentServiceTest, softDelete, "soft delete keeps the node, hides author and text") {
    CommentService s;
    Comment root = s.addComment("p", "ana", "secret");
    s.reply(root.id, "ben", "child");
    s.remove(root.id, "ana");
    CommentNode node = s.getThread("p")[0];
    ASSERT_TRUE(node.comment.deleted, "deleted");
    ASSERT_EQ(std::string("[deleted]"), node.comment.text, "text hidden");
    ASSERT_FALSE(node.comment.author.has_value(), "author hidden");
    ASSERT_EQ(size_t{1}, node.replies.size(), "replies survive");
    ASSERT_EQ(1, s.countVisible("p"), "visible count");
    s.remove(root.id, "ana");  // no-op
    ASSERT_THROWS(InvalidState, s.reply(root.id, "ben", "x"), "reply to deleted");
}

LAB_TEST(CommentServiceTest, deleteAuthorization, "only the author can delete") {
    CommentService s;
    Comment c = s.addComment("p", "ana", "mine");
    ASSERT_THROWS(PermissionDenied, s.remove(c.id, "ben"), "not the author");
    ASSERT_THROWS(std::out_of_range, s.remove("c42", "ana"), "unknown comment");
    ASSERT_EQ(1, s.countVisible("p"), "still visible");
}

LAB_TEST(CommentServiceTest, snapshot, "returned thread is a snapshot") {
    CommentService s;
    Comment c = s.addComment("p", "u", "first");
    auto before = s.getThread("p");
    s.addComment("p", "u", "second");
    s.reply(c.id, "u", "reply");
    ASSERT_EQ(size_t{1}, before.size(), "old list must not grow");
    ASSERT_TRUE(before[0].replies.empty(), "old replies must not grow");
}

LAB_TEST(CommentServiceTest, concurrency, "concurrent writers get unique sequential ids") {
    CommentService s;
    Comment root = s.addComment("p", "u", "root");
    std::mutex mu;
    std::set<std::string> ids;
    labtest::runConcurrently(16, [&](int i) {
        for (int k = 0; k < 100; k++) {
            std::string author = "u" + std::to_string(i);
            Comment c = k % 2 == 0 ? s.addComment("p", author, "t") : s.reply(root.id, author, "r");
            std::lock_guard<std::mutex> lock(mu);
            ids.insert(c.id);
        }
    });
    ASSERT_EQ(size_t{1600}, ids.size(), "all ids unique");
    for (int n = 2; n <= 1601; n++) ASSERT_TRUE(ids.count("c" + std::to_string(n)) == 1, "missing c" + std::to_string(n));
    ASSERT_EQ(1601, s.countVisible("p"), "visible count");
    ASSERT_EQ(size_t{800}, s.getThread("p")[0].replies.size(), "replies on root");
}
