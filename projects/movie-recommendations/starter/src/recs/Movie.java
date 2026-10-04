package recs;

import java.util.Set;

public record Movie(String id, String title, Set<String> genres) {
}
