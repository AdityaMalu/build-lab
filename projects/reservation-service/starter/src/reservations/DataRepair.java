package reservations;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DataRepair {

    private DataRepair() {}

    public static RepairResult repair(List<Reservation> export) {
        List<Reservation> rows = new ArrayList<>(export);
        rows.sort(Comparator.comparing(Reservation::guest));

        Map<String, List<Reservation>> keptByRoom = new HashMap<>();
        List<Reservation> out = new ArrayList<>();
        List<String> cancelled = new ArrayList<>();
        for (Reservation r : rows) {
            if (r.status() == Status.ACTIVE) {
                List<Reservation> kept = keptByRoom.computeIfAbsent(r.roomId(), k -> new ArrayList<>());
                boolean clash = false;
                for (Reservation k : kept) {
                    if (r.checkIn().isBefore(k.checkOut()) && k.checkIn().isBefore(r.checkOut())) {
                        clash = true;
                        break;
                    }
                }
                if (clash) {
                    // TODO: ops wants these converted to CANCELLED, not silently dropped
                    continue;
                }
                kept.add(r);
            }
            out.add(r);
        }
        return new RepairResult(out, cancelled);
    }
}
