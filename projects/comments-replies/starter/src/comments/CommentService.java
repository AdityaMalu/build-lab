package comments;

import java.util.List;

public class CommentService {

    public static final int MAX_LENGTH = 500;
    public static final int MAX_DEPTH = 3;

    public Comment addComment(String postId, String author, String text) {
        throw new UnsupportedOperationException("TODO");
    }

    public Comment reply(String parentId, String author, String text) {
        throw new UnsupportedOperationException("TODO");
    }

    public List<CommentNode> getThread(String postId) {
        throw new UnsupportedOperationException("TODO");
    }

    public void delete(String commentId, String requester) {
        throw new UnsupportedOperationException("TODO");
    }

    public int countVisible(String postId) {
        throw new UnsupportedOperationException("TODO");
    }
}
