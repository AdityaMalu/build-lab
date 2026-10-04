package reservations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DataRepair {

    private DataRepair() {}

    public static RepairResult repair(List<Reservation> export) {
        // 1. dedupe by id, keeping the smallest seq
        Map<String, Reservation> unique = new LinkedHashMap<>();
        for (Reservation r : export) {
            unique.merge(r.id(), r, (a, b) -> a.seq() <= b.seq() ? a : b);
        }
        List<Reservation> rows = new ArrayList<>(unique.values());
        // 2. earliest booking wins
        rows.sort(Comparator.comparingLong(Reservation::seq));

        Map<String, List<Reservation>> keptByRoom = new HashMap<>();
        List<Reservation> out = new ArrayList<>();
        List<String> cancelled = new ArrayList<>();
        for (Reservation r : rows) {
            if (r.status() == Status.ACTIVE) {
                List<Reservation> kept = keptByRoom.computeIfAbsent(r.roomId(), k -> new ArrayList<>());
                boolean clash = false;
                for (Reservation k : kept) {
                    if (ReservationService.overlaps(r.checkIn(), r.checkOut(), k.checkIn(), k.checkOut())) {
                        clash = true;
                        break;
                    }
                }
                if (clash) {
                    out.add(r.withStatus(Status.CANCELLED));
                    cancelled.add(r.id());
                    continue;
                }
                kept.add(r);
            }
            out.add(r);
        }
        return new RepairResult(out, cancelled);
    }
}
