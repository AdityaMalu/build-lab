package moderation;

@FunctionalInterface
public interface ModerationListener {
    void onDecision(Submission decided);
}
