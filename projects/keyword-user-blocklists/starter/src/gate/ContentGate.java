package gate;

public class ContentGate {

    public static final int STRIKE_LIMIT = 3;
    public static final long STRIKE_WINDOW_MS = 60 * 60 * 1000L;
    public static final long AUTO_BLOCK_MS = 24 * 60 * 60 * 1000L;

    public ContentGate(TimeSource time) {
        // TODO
    }

    public void addKeyword(String phrase) {
        throw new UnsupportedOperationException("TODO");
    }

    public void removeKeyword(String phrase) {
        throw new UnsupportedOperationException("TODO");
    }

    public void blockUser(String userId, long durationMillis) {
        throw new UnsupportedOperationException("TODO");
    }

    public void unblockUser(String userId) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean isBlocked(String userId) {
        throw new UnsupportedOperationException("TODO");
    }

    public Decision submit(String userId, String text) {
        throw new UnsupportedOperationException("TODO");
    }

    public int activeStrikes(String userId) {
        throw new UnsupportedOperationException("TODO");
    }
}
