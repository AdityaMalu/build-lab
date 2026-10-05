package comments;

public record Comment(
        String id,
        String postId,
        String parentId,   // null for top-level comments
        String author,     // null once deleted
        String text,       // "[deleted]" once deleted
        long seq,
        int depth,
        boolean deleted) {
}
