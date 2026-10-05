package reset;

import java.security.SecureRandom;

@FunctionalInterface
public interface CodeSource {
    /** Uniform value in [0, bound). */
    int nextInt(int bound);

    static CodeSource secure() {
        SecureRandom rnd = new SecureRandom();
        return rnd::nextInt;
    }
}
