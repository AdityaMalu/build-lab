package dedup;

import java.io.IOException;
import java.nio.file.Path;

public class Deduplicator {

    public Deduplicator(ContentHasher hasher, int threads) {
        // TODO
    }

    public DedupReport scan(Path root) throws IOException {
        // TODO size -> hash (concurrently) -> exact compare
        throw new UnsupportedOperationException("TODO");
    }
}
