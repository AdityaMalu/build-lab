package comments;

import java.util.List;

public record CommentNode(Comment comment, List<CommentNode> replies) {
}
