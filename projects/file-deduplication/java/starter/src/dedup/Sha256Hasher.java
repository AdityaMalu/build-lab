package dedup;

import java.io.IOException;
import java.nio.file.Path;

public class Sha256Hasher implements ContentHasher {

    @Override
    public String hash(Path file) throws IOException {
        // TODO stream the file through MessageDigest.getInstance("SHA-256") and return lowercase hex
        throw new UnsupportedOperationException("TODO");
    }
}
