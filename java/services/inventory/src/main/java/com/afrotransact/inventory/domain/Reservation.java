package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * All lines of one order, reserved together or not at all. The order id is the idempotency key: a retry for the
 * same order returns this reservation instead of reserving again.
 *
 * <p>Lines may carry a group (orders sends its line id). Groups are committed (shipped) or released one shipment
 * at a time; the reservation stays HELD while any line is held and then ends COMMITTED (something shipped) or
 * RELEASED.
 *
 * @param expiresAt when a HELD reservation is released automatically; null once payment confirmed the order
 */
public record Reservation(UUID id, String orderId, ReservationStatus status, String idempotencyKey,
                          Instant expiresAt, Instant createdAt, Instant updatedAt, List<ReservationLine> lines,
                          long version) {

    public Reservation {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static Reservation hold(UUID id, String orderId, String idempotencyKey, Instant now, Instant expiresAt) {
        return new Reservation(id, orderId, ReservationStatus.HELD, idempotencyKey, expiresAt, now, now, List.of(), 0);
    }

    public Reservation withLines(List<ReservationLine> newLines) {
        return new Reservation(id, orderId, status, idempotencyKey, expiresAt, createdAt, updatedAt, newLines, version);
    }

    /** Payment authorised: keep the hold until shipment instead of letting the checkout TTL release it. */
    public Reservation confirm(Instant now) {
        requireHeld("confirm");
        return new Reservation(id, orderId, status, idempotencyKey, null, createdAt, now, lines, version);
    }

    /** Ships every line still held. */
    public Reservation commit(Instant now) {
        requireHeld("commit");
        return new Reservation(id, orderId, ReservationStatus.COMMITTED, idempotencyKey, expiresAt, createdAt, now,
                mark(lines, null, ReservationLine.LineStatus.COMMITTED), version);
    }

    /** Gives back every line still held; ends COMMITTED when some group already shipped. */
    public Reservation release(Instant now) {
        requireHeld("release");
        List<ReservationLine> next = mark(lines, null, ReservationLine.LineStatus.RELEASED);
        return new Reservation(id, orderId, anyCommitted(next) ? ReservationStatus.COMMITTED
                : ReservationStatus.RELEASED, idempotencyKey, expiresAt, createdAt, now, next, version);
    }

    public Reservation expire(Instant now) {
        requireHeld("expire");
        return new Reservation(id, orderId, ReservationStatus.EXPIRED, idempotencyKey, expiresAt, createdAt, now,
                mark(lines, null, ReservationLine.LineStatus.RELEASED), version);
    }

    /**
     * Ships the held lines of the groups. Lines of the groups already committed are skipped (idempotent); a released
     * line cannot be shipped.
     */
    public Reservation commitGroups(Set<String> groups, Instant now) {
        List<ReservationLine> selected = selected(groups);
        if (selected.stream().anyMatch(l -> l.status() == ReservationLine.LineStatus.RELEASED)) {
            throw new InventoryStateConflict("inventory.group-released", "Some lines of " + groups + " on order "
                    + orderId + " were released and cannot be shipped");
        }
        if (selected.stream().noneMatch(ReservationLine::isHeld)) {
            return this;
        }
        requireHeld("commit");
        return afterGroupChange(mark(lines, groups, ReservationLine.LineStatus.COMMITTED), now);
    }

    /** Gives back the held lines of the groups. Released lines are skipped; a shipped line cannot be released. */
    public Reservation releaseGroups(Set<String> groups, Instant now) {
        List<ReservationLine> selected = selected(groups);
        if (selected.stream().anyMatch(l -> l.status() == ReservationLine.LineStatus.COMMITTED)) {
            throw new InventoryStateConflict("inventory.group-committed", "Some lines of " + groups + " on order "
                    + orderId + " already shipped; returns go through POST /v1/returns");
        }
        if (selected.stream().noneMatch(ReservationLine::isHeld)) {
            return this;
        }
        requireHeld("release");
        return afterGroupChange(mark(lines, groups, ReservationLine.LineStatus.RELEASED), now);
    }

    /** The held lines that an operation on {@code groups} (the whole order when null) would change. */
    public List<ReservationLine> heldLines(Set<String> groups) {
        return lines.stream().filter(ReservationLine::isHeld).filter(l -> groups == null || l.inAny(groups)).toList();
    }

    /** The groups (of those asked) that have no line in this reservation. */
    public Set<String> unknownGroups(Set<String> groups) {
        Set<String> known = new HashSet<>();
        lines.forEach(l -> {
            if (l.group() != null) {
                known.add(l.group());
            }
        });
        return groups.stream().filter(g -> !known.contains(g)).collect(Collectors.toSet());
    }

    /** Lines that shipped. */
    public List<ReservationLine> committedLines() {
        return lines.stream().filter(l -> l.status() == ReservationLine.LineStatus.COMMITTED).toList();
    }

    public boolean isHeld() {
        return status == ReservationStatus.HELD;
    }

    public boolean expiredAt(Instant now) {
        return isHeld() && expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Total landed cost of everything allocated, per currency is left to the caller. */
    public long costMinor() {
        return lines.stream().mapToLong(ReservationLine::costMinor).sum();
    }

    private Reservation afterGroupChange(List<ReservationLine> next, Instant now) {
        ReservationStatus nextStatus = next.stream().anyMatch(ReservationLine::isHeld) ? ReservationStatus.HELD
                : anyCommitted(next) ? ReservationStatus.COMMITTED : ReservationStatus.RELEASED;
        return new Reservation(id, orderId, nextStatus, idempotencyKey, expiresAt, createdAt, now, next, version);
    }

    private List<ReservationLine> selected(Set<String> groups) {
        if (groups == null || groups.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Name at least one group");
        }
        return lines.stream().filter(l -> l.inAny(groups)).toList();
    }

    private static List<ReservationLine> mark(List<ReservationLine> lines, Set<String> groups,
                                              ReservationLine.LineStatus next) {
        return lines.stream().map(l -> l.isHeld() && (groups == null || l.inAny(groups)) ? l.withStatus(next) : l)
                .toList();
    }

    private static boolean anyCommitted(List<ReservationLine> lines) {
        return lines.stream().anyMatch(l -> l.status() == ReservationLine.LineStatus.COMMITTED);
    }

    private void requireHeld(String action) {
        if (status != ReservationStatus.HELD) {
            throw new InventoryStateConflict("inventory.reservation-not-held", "Cannot " + action + " reservation for "
                    + "order " + orderId + ": it is " + status);
        }
    }
}
