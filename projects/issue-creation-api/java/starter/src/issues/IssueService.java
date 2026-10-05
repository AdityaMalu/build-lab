package issues;

import java.util.Map;

public class IssueService {

    public IssueService(Map<String, User> tokens) {
        // TODO
    }

    public User authenticate(String token) {
        throw new UnsupportedOperationException("TODO");
    }

    public CreateResult create(String token, String idempotencyKey, String title, String body) {
        throw new UnsupportedOperationException("TODO");
    }

    public Issue get(String token, long id) {
        throw new UnsupportedOperationException("TODO");
    }

    public Issue update(String token, long id, long expectedVersion, String newTitle) {
        throw new UnsupportedOperationException("TODO");
    }

    public Page list(String token, int limit, String cursor) {
        throw new UnsupportedOperationException("TODO");
    }
}
