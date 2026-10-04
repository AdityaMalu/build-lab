package moderation;

public record Submission(String id, String author, String text, Status status, String moderator, String reason) {

    Submission decide(Status s, String by, String why) {
        return new Submission(id, author, text, s, by, why);
    }
}
