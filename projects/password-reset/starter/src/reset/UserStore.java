package reset;

public interface UserStore {
    boolean exists(String email);

    void setPasswordHash(String email, String hash);

    String passwordHash(String email);
}
