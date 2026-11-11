package com.afrotransact.orders.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A shopper's request for a delivery quote and what became of it (listing rules D20): the cart and address it is
 * for, who on staff has it, the agreed amount and how long it is valid, and the order that used it. All transitions
 * are methods here; they refuse what the state does not allow and return a new value (the {@code version} is the
 * stored row's, for optimistic locking).
 */
public record DeliveryQuote(String id, String reference, String customerId, QuoteStatus status, QuoteSnapshot snapshot,
                            Answer answer, Decline decline, String usedOrderId, Instant usedAt, Holder holder,
                            Instant stageEnteredAt, Escalation escalation, Instant createdAt, Instant updatedAt,
                            long version) {

    public DeliveryQuote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(snapshot, "snapshot");
        holder = holder == null ? Holder.NONE : holder;
        escalation = escalation == null ? Escalation.NONE : escalation;
    }

    /** The agreed amount.
     *
     * @param reason why this amount (required, audited) */
    public record Answer(long amountMinor, String reason, String byId, String byName, Instant at, Instant expiresAt) {
    }

    /** A refusal to quote. */
    public record Decline(String reason, String byId, String byName, Instant at) {
    }

    /** Who has the case; {@link #NONE} when it is in the pool. */
    public record Holder(String assigneeId, String assigneeName, String assignedById, String assignedByName,
                         Instant assignedAt) {

        public static final Holder NONE = new Holder(null, null, null, null, null);

        public boolean assigned() {
            return assigneeId != null;
        }
    }

    /** Escalation level 0 (none), 1 or 2 and whom it went to (names for display). */
    public record Escalation(int level, Instant at, String reason, List<String> targetIds, List<String> targetNames) {

        public static final Escalation NONE = new Escalation(0, null, null, List.of(), List.of());

        public Escalation {
            targetIds = targetIds == null ? List.of() : List.copyOf(targetIds);
            targetNames = targetNames == null ? List.of() : List.copyOf(targetNames);
        }
    }

    /** A new request nobody holds yet. */
    public static DeliveryQuote requested(String id, String reference, String customerId, QuoteSnapshot snapshot,
                                          Instant now) {
        return new DeliveryQuote(id, reference, customerId, QuoteStatus.REQUESTED, snapshot, null, null, null, null,
                Holder.NONE, now, Escalation.NONE, now, now, 0);
    }

    public boolean assigned() {
        return holder.assigned();
    }

    public boolean escalatedTo(String userId) {
        return escalation.level() > 0 && escalation.targetIds().contains(userId);
    }

    /** The agreed amount can still be used at {@code now}. */
    public boolean usable(Instant now) {
        return status == QuoteStatus.READY && answer != null && answer.expiresAt().isAfter(now);
    }

    /** Gives the case to a person; the wait does not restart. */
    public DeliveryQuote assign(Holder next, Instant now) {
        requireAwaitingStaff("assigned");
        return copy(QuoteStatus.IN_PROGRESS, answer, decline, usedOrderId, usedAt, next, stageEnteredAt, escalation,
                now);
    }

    /** Back to the pool. */
    public DeliveryQuote release(Instant now) {
        requireAwaitingStaff("released");
        return copy(QuoteStatus.REQUESTED, answer, decline, usedOrderId, usedAt, Holder.NONE, stageEnteredAt,
                escalation, now);
    }

    /** Raises the escalation to {@code level}; never lowers it. */
    public DeliveryQuote escalate(Escalation next, Instant now) {
        requireAwaitingStaff("escalated");
        if (next.level() <= escalation.level()) {
            return this;
        }
        return copy(status, answer, decline, usedOrderId, usedAt, holder, stageEnteredAt, next, now);
    }

    /** Staff agreed {@code amount}: the quote is ready and the wait is now the shopper's. */
    public DeliveryQuote agree(Answer agreed, Instant now) {
        requireAwaitingStaff("agreed");
        return copy(QuoteStatus.READY, agreed, null, null, null, holder, now, Escalation.NONE, now);
    }

    public DeliveryQuote decline(Decline refused, Instant now) {
        requireAwaitingStaff("declined");
        return copy(QuoteStatus.DECLINED, null, refused, null, null, holder, now, Escalation.NONE, now);
    }

    /** The validity ended before an order used it. */
    public DeliveryQuote expire(Instant now) {
        if (status != QuoteStatus.READY) {
            throw new OrdersStateConflict("orders.quote-not-ready", reference + " is " + status.label().toLowerCase());
        }
        return copy(QuoteStatus.EXPIRED, answer, decline, null, null, holder, now, escalation, now);
    }

    /** An order carries the amount. */
    public DeliveryQuote use(String orderId, Instant now) {
        if (!usable(now)) {
            throw new OrdersStateConflict("orders.quote-not-usable", reference + " can't be used any more");
        }
        return copy(QuoteStatus.USED, answer, decline, orderId, now, holder, stageEnteredAt, escalation, now);
    }

    /** The order that used the quote did not complete: usable again (the validity still applies). */
    public DeliveryQuote restore(Instant now) {
        if (status != QuoteStatus.USED) {
            throw new OrdersStateConflict("orders.quote-not-used", reference + " has not been used");
        }
        return copy(QuoteStatus.READY, answer, decline, null, null, holder, stageEnteredAt, escalation, now);
    }

    private void requireAwaitingStaff(String what) {
        if (!status.awaitingStaff()) {
            throw new OrdersStateConflict("orders.quote-closed", reference + " can't be " + what + ": it is "
                    + status.label().toLowerCase());
        }
    }

    private DeliveryQuote copy(QuoteStatus s, Answer a, Decline d, String order, Instant orderAt, Holder h,
                               Instant entered, Escalation e, Instant now) {
        return new DeliveryQuote(id, reference, customerId, s, snapshot, a, d, order, orderAt, h, entered, e,
                createdAt, now, version);
    }
}
