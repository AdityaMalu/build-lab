package watchlists;

import java.util.List;

public record Watchlist(String id, String owner, String name, List<String> movieIds) {
}
