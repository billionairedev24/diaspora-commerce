package com.afrotransact.bookings.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.Activity;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingStatus;
import com.afrotransact.bookings.domain.BusyInterval;

/**
 * Persistence port for bookings. Inserting or updating a booking also stores its new history lines and the time it
 * blocks; overlapping blocks of one team member are refused by a database exclusion constraint, reported as a
 * {@code bookings.slot-taken} conflict.
 */
public interface BookingRepository {

    /** Next human reference, e.g. {@code BK-24018}. */
    String nextReference();

    Booking insert(Booking booking);

    Booking update(Booking booking);

    Optional<Booking> find(UUID id);

    /** Locks the booking row (SELECT … FOR UPDATE). */
    Optional<Booking> lock(UUID id);

    Optional<Booking> findByReference(String reference);

    /** Time the member's active bookings block between {@code from} and {@code to}, except {@code excludeId}. */
    List<BusyInterval> busy(UUID memberId, Instant from, Instant to, UUID excludeId);

    /** Active bookings of the member starting in [from, to), except {@code excludeId}. */
    int countStarting(UUID memberId, Instant from, Instant to, UUID excludeId);

    List<Booking> forCustomer(String customerId);

    /** Bookings of an organisation starting in [from, to), filtered by member and status when given. */
    List<Booking> forProvider(String orgId, Instant from, Instant to, UUID memberId, BookingStatus status);

    /** Ids of bookings in {@code status} whose deadline column passed (jobs); skips rows locked by other workers. */
    List<UUID> dueForExpiry(BookingStatus status, Instant now, int limit);

    /** Confirmed bookings that ended before {@code endedBefore} (auto-complete job). */
    List<UUID> dueForCompletion(Instant endedBefore, int limit);

    List<Activity> history(UUID bookingId);
}
