package reservations;

import java.time.LocalDate;

public record Reservation(
        String id,
        String roomId,
        String guest,
        LocalDate checkIn,
        LocalDate checkOut,
        long seq,
        Status status) {

    public Reservation withStatus(Status s) {
        return new Reservation(id, roomId, guest, checkIn, checkOut, seq, s);
    }
}
