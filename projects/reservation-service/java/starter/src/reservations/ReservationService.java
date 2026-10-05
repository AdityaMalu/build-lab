package reservations;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public class ReservationService {

    private final Set<String> rooms;
    private final Map<String, Reservation> byId = new HashMap<>();
    private final Map<String, List<Reservation>> byRoom = new HashMap<>();
    private long seq = 0;

    public ReservationService(Set<String> rooms) {
        this.rooms = Set.copyOf(rooms);
        for (String r : rooms) byRoom.put(r, new ArrayList<>());
    }

    public Reservation reserve(String roomId, String guest, LocalDate checkIn, LocalDate checkOut) {
        if (!rooms.contains(roomId)) throw new IllegalArgumentException("unknown room " + roomId);
        if (guest == null || guest.isBlank()) throw new IllegalArgumentException("guest required");
        if (checkIn == null || checkOut == null || checkOut.isBefore(checkIn)) {
            throw new IllegalArgumentException("bad dates");
        }
        if (!isAvailable(roomId, checkIn, checkOut)) {
            throw new IllegalStateException("room unavailable");
        }
        seq++;
        Reservation r = new Reservation("R" + seq, roomId, guest, checkIn, checkOut, seq, Status.ACTIVE);
        byId.put(r.id(), r);
        byRoom.get(roomId).add(r);
        return r;
    }

    public void cancel(String reservationId) {
        Reservation r = byId.get(reservationId);
        if (r == null) throw new NoSuchElementException("no reservation " + reservationId);
        byId.put(reservationId, r.withStatus(Status.CANCELLED));
    }

    public Reservation get(String reservationId) {
        Reservation r = byId.get(reservationId);
        if (r == null) throw new NoSuchElementException("no reservation " + reservationId);
        return r;
    }

    public List<Reservation> activeForRoom(String roomId) {
        List<Reservation> out = new ArrayList<>();
        for (Reservation r : byRoom.getOrDefault(roomId, List.of())) {
            if (r.status() == Status.ACTIVE) out.add(r);
        }
        out.sort(Comparator.comparing(Reservation::checkIn));
        return out;
    }

    public boolean isAvailable(String roomId, LocalDate checkIn, LocalDate checkOut) {
        for (Reservation r : byRoom.getOrDefault(roomId, List.of())) {
            if (r.status() != Status.ACTIVE) continue;
            boolean overlaps = !(checkOut.isBefore(r.checkIn()) || checkIn.isAfter(r.checkOut()));
            if (overlaps) return false;
        }
        return true;
    }
}
