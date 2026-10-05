package comments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class CommentService {

    public static final int MAX_LENGTH = 500;
    public static final int MAX_DEPTH = 3;
    private static final String ROOT = "\u0000root:";

    private final Map<String, Comment> byId = new HashMap<>();
    /** parent id (or ROOT + postId for top level) -> child ids in creation order */
    private final Map<String, List<String>> children = new HashMap<>();
    private long nextSeq = 1;

    public synchronized Comment addComment(String postId, String author, String text) {
        requireNonBlank(postId, "postId");
        requireNonBlank(author, "author");
        String body = cleanText(text);
        return insert(postId, null, author, body, 0);
    }

    public synchronized Comment reply(String parentId, String author, String text) {
        requireNonBlank(author, "author");
        String body = cleanText(text);
        Comment parent = byId.get(parentId);
        if (parent == null) throw new NoSuchElementException("no comment " + parentId);
        if (parent.deleted()) throw new IllegalStateException("cannot reply to a deleted comment");
        if (parent.depth() >= MAX_DEPTH) throw new IllegalStateException("max depth reached");
        return insert(parent.postId(), parent.id(), author, body, parent.depth() + 1);
    }

    public synchronized List<CommentNode> getThread(String postId) {
        return build(ROOT + postId);
    }

    public synchronized void delete(String commentId, String requester) {
        Comment c = byId.get(commentId);
        if (c == null) throw new NoSuchElementException("no comment " + commentId);
        if (c.deleted()) return;
        if (!c.author().equals(requester)) throw new SecurityException("only the author may delete");
        byId.put(commentId, new Comment(c.id(), c.postId(), c.parentId(), null, "[deleted]",
                c.seq(), c.depth(), true));
    }

    public synchronized int countVisible(String postId) {
        int n = 0;
        for (Comment c : byId.values()) {
            if (c.postId().equals(postId) && !c.deleted()) n++;
        }
        return n;
    }

    private Comment insert(String postId, String parentId, String author, String body, int depth) {
        long seq = nextSeq++;
        Comment c = new Comment("c" + seq, postId, parentId, author, body, seq, depth, false);
        byId.put(c.id(), c);
        String key = parentId == null ? ROOT + postId : parentId;
        children.computeIfAbsent(key, k -> new ArrayList<>()).add(c.id());
        return c;
    }

    private List<CommentNode> build(String key) {
        List<CommentNode> out = new ArrayList<>();
        for (String id : children.getOrDefault(key, List.of())) {
            out.add(new CommentNode(byId.get(id), build(id)));
        }
        return List.copyOf(out);
    }

    private static void requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(name + " required");
    }

    private static String cleanText(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text required");
        String t = text.trim();
        if (t.length() > MAX_LENGTH) throw new IllegalArgumentException("text too long");
        return t;
    }
}
