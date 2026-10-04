package reservations;

import java.util.List;

public record RepairResult(List<Reservation> rows, List<String> cancelledIds) {
}
