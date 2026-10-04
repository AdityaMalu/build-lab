package dedup;

import java.io.IOException;
import java.nio.file.Path;

public interface ContentHasher {
    String hash(Path file) throws IOException;
}
