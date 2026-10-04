package dedup;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record DedupReport(List<List<Path>> duplicateGroups, Map<Path, String> failures, long reclaimableBytes) {
}
