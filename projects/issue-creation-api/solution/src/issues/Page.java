package issues;

import java.util.List;

public record Page(List<Issue> items, String nextCursor) {
}
