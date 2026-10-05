package leaderboard;

import java.util.List;

public record Page(int pageNumber, int pageSize, int totalPlayers, List<RankedEntry> entries) {
}
