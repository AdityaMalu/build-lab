# Reservation Service Incident

**Scenario.** You're on call. Support reports that the hotel booking service:
- sometimes **double-books** a room during traffic spikes,
- refuses perfectly valid **back-to-back** bookings (one guest checks out the morning another checks in),
- still shows **cancelled** bookings as blocking the room,
- and last week's nightly export contains duplicate and overlapping rows.

The code in your workspace is the **production code**: it compiles and mostly works. Find and fix the bugs,
then finish the data-repair tool so ops can clean the export.

## Domain (package `reservations`)
- A stay is the half-open date range **`[checkIn, checkOut)`**: the guest occupies nights from `checkIn` up to but
  not including `checkOut`. So `[1st, 3rd)` and `[3rd, 5th)` do **not** overlap.
- `Reservation` is an immutable record; `Status` is `ACTIVE` or `CANCELLED`.

## Expected behaviour of `ReservationService`
| Method | Contract |
|---|---|
| `ReservationService(Set<String> rooms)` | Known room ids |
| `reserve(room, guest, in, out)` | Unknown room, blank guest, null dates or `out <= in` → `IllegalArgumentException`. Overlap with an **active** stay on that room → `IllegalStateException`. Ids are `"R1"`, `"R2"`, ... |
| `cancel(id)` | Unknown → `NoSuchElementException`. Cancelling twice is a no-op. Frees the dates immediately. |
| `get(id)` | Current state of the reservation (reflects cancellation) |
| `activeForRoom(room)` | Active stays for that room sorted by `checkIn` |
| `isAvailable(room, in, out)` | Consistent with `reserve` |

All methods may be called from many threads at once. **No double booking, ever.**

## `DataRepair.repair(List<Reservation> export)` → `RepairResult`
1. Rows with the **same id** are duplicates: keep a single copy (the one with the smallest `seq`).
2. For each room, walk **active** reservations in `seq` order (earliest booking wins). Any active reservation that
   overlaps an already-kept active one is converted to `CANCELLED`, and its id goes into `cancelledIds` (in `seq` order).
3. Rows already `CANCELLED` are kept as they are.
4. `rows` in the result are sorted by `seq`.

## How to approach it
Run the tests first and read the failures. Each one points at a symptom from the incident report.
Interviewers care about *how* you narrow it down: reproduce, form a hypothesis, fix, add a regression test.
