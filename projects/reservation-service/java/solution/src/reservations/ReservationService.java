package reservations;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Fixes vs. the incident version:
 *  1. Half-open overlap check: [a,b) and [c,d) overlap iff a < d && c < b (back-to-back stays are fine).
 *  2. checkOut must be strictly after checkIn.
 *  3. Single source of truth: the room index stores ids, status always comes from byId,
 *     so a cancellation is visible everywhere immediately.
 *  4. All state access is synchronized, making check-then-insert atomic (no double booking).
 */
public class ReservationService {

    private final Set<String> rooms;
    private final Map<String, Reservation> byId = new HashMap<>();
    private final Map<String, List<String>> idsByRoom = new HashMap<>();
    private long seq = 0;

    public ReservationService(Set<String> rooms) {
        this.rooms = Set.copyOf(rooms);
        for (String r : rooms) idsByRoom.put(r, new ArrayList<>());
    }

    public synchronized Reservation reserve(String roomId, String guest, LocalDate checkIn, LocalDate checkOut) {
        if (roomId == null || !rooms.contains(roomId)) throw new IllegalArgumentException("unknown room " + roomId);
        if (guest == null || guest.isBlank()) throw new IllegalArgumentException("guest required");
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("bad dates");
        }
        if (!isAvailable(roomId, checkIn, checkOut)) {
            throw new IllegalStateException("room unavailable");
        }
        seq++;
        Reservation r = new Reservation("R" + seq, roomId, guest, checkIn, checkOut, seq, Status.ACTIVE);
        byId.put(r.id(), r);
        idsByRoom.get(roomId).add(r.id());
        return r;
    }

    public synchronized void cancel(String reservationId) {
        Reservation r = byId.get(reservationId);
        if (r == null) throw new NoSuchElementException("no reservation " + reservationId);
        if (r.status() == Status.CANCELLED) return;
        byId.put(reservationId, r.withStatus(Status.CANCELLED));
    }

    public synchronized Reservation get(String reservationId) {
        Reservation r = byId.get(reservationId);
        if (r == null) throw new NoSuchElementException("no reservation " + reservationId);
        return r;
    }

    public synchronized List<Reservation> activeForRoom(String roomId) {
        List<Reservation> out = new ArrayList<>();
        for (String id : idsByRoom.getOrDefault(roomId, List.of())) {
            Reservation r = byId.get(id);
            if (r.status() == Status.ACTIVE) out.add(r);
        }
        out.sort(Comparator.comparing(Reservation::checkIn));
        return out;
    }

    public synchronized boolean isAvailable(String roomId, LocalDate checkIn, LocalDate checkOut) {
        for (String id : idsByRoom.getOrDefault(roomId, List.of())) {
            Reservation r = byId.get(id);
            if (r.status() == Status.ACTIVE && overlaps(checkIn, checkOut, r.checkIn(), r.checkOut())) {
                return false;
            }
        }
        return true;
    }

    static boolean overlaps(LocalDate aIn, LocalDate aOut, LocalDate bIn, LocalDate bOut) {
        return aIn.isBefore(bOut) && bIn.isBefore(aOut);
    }
}
