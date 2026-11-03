package com.afrotransact.deliveryruns.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A scheduled community delivery run for one location: customers preorder until the cutoff, sellers' items are
 * collected in one sweep and everything is delivered in the window.
 *
 * @param id               run id
 * @param locationId       the location served
 * @param status           lifecycle status ({@link RunStatus})
 * @param terms            window, cutoff, fee, minimum, capacity, drop mode and cutoff policy
 * @param originalCutoffAt the cutoff the run was opened with (before extensions)
 * @param extensions       how many times the cutoff was moved
 * @param reservedOrders   orders holding a slot (RESERVED or CONFIRMED)
 * @param reservedPackages packages of those orders
 * @param cancelReason     why the run was cancelled
 * @param reminderSentFor  the cutoff the 24-hour reminder was sent for (re-armed by an extension)
 * @param estimate         one-way route from the origin, captured when the run was created (null if geo was down)
 * @param milestones       when each lifecycle step happened
 * @param createdAt        created
 * @param updatedAt        last change
 * @param version          optimistic lock
 */
public record Run(UUID id, UUID locationId, RunStatus status, RunTerms terms, Instant originalCutoffAt, int extensions,
                  int reservedOrders, int reservedPackages, String cancelReason, Instant reminderSentFor,
                  RouteEstimate estimate, RunMilestones milestones, Instant createdAt, Instant updatedAt,
                  long version) {

    public static final String REASON_MINIMUM_NOT_MET = "MINIMUM_NOT_MET";

    public Run {
        if (reservedOrders < 0 || reservedPackages < 0) {
            throw new IllegalStateException("Reserved counts cannot be negative");
        }
        milestones = milestones == null ? RunMilestones.NONE : milestones;
    }

    /** A new DRAFT run. */
    public static Run draft(UUID id, UUID locationId, RunTerms terms, RouteEstimate estimate, Instant now) {
        return new Run(id, locationId, RunStatus.DRAFT, terms, terms.cutoffAt(), 0, 0, 0, null, null, estimate,
                RunMilestones.NONE, now, now, 0);
    }

    /**
     * A run created when a pre-order window closes: ordering already happened in the window (there is no minimum),
     * so the run starts CONFIRMED with the window's paid orders and goes on to collecting and delivery.
     */
    public static Run confirmedFromWindow(UUID id, UUID locationId, RunTerms terms, RouteEstimate estimate, int orders,
                                          int packages, Instant now) {
        RunMilestones m = RunMilestones.NONE.announced(now).opened(now).cutOff(now).confirmed(now);
        return new Run(id, locationId, RunStatus.CONFIRMED, terms, terms.cutoffAt(), 0, orders, packages, null, null,
                estimate, m, now, now, 0);
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------

    public Run announce(Instant now) {
        Run next = move(RunStatus.ANNOUNCED, now);
        return next.with(next.milestones.announced(now));
    }

    public Run open(Instant now) {
        if (!terms.cutoffAt().isAfter(now)) {
            throw new RuleViolation("delivery-runs.cutoff-passed", "The cutoff has passed; extend the run instead");
        }
        Run next = move(RunStatus.OPEN, now);
        RunMilestones m = status == RunStatus.DRAFT ? next.milestones.announced(now) : next.milestones;
        return next.with(m.opened(now));
    }

    /** Ordering closes (the cutoff job, or staff confirming early). */
    public Run cutOff(Instant now) {
        Run next = move(RunStatus.CUT_OFF, now);
        return next.with(next.milestones.cutOff(now));
    }

    public Run confirm(Instant now) {
        Run next = move(RunStatus.CONFIRMED, now);
        return next.with(next.milestones.confirmed(now));
    }

    /**
     * Moves the cutoff (and window) later; the run takes orders again until the new cutoff.
     *
     * @param newCutoff         after now and after the current cutoff
     * @param newWindow         after the new cutoff
     * @param newSellerPickupAt optional seller pickup in the new schedule
     */
    public Run extend(Instant newCutoff, DeliveryWindow newWindow, Instant newSellerPickupAt, Instant now) {
        if (!status.canMoveTo(RunStatus.EXTENDED)) {
            throw new InvalidTransitionException(status.name(), RunStatus.EXTENDED.name());
        }
        if (newCutoff == null || !newCutoff.isAfter(now) || !newCutoff.isAfter(terms.cutoffAt())) {
            throw new RuleViolation("delivery-runs.invalid-extension",
                    "The new cutoff must be in the future and later than the current one");
        }
        RunTerms moved = terms.reschedule(newCutoff, newWindow, newSellerPickupAt);
        return new Run(id, locationId, RunStatus.EXTENDED, moved, originalCutoffAt, extensions + 1, reservedOrders,
                reservedPackages, cancelReason, reminderSentFor, estimate, milestones, createdAt, now, version);
    }

    /** Extension by the run's own {@link RunTerms#extendDays()} (the cutoff policy). */
    public Run extendByPolicy(Instant now) {
        Duration by = Duration.ofDays(terms.extendDays());
        Instant pickup = terms.sellerPickupAt() == null ? null : terms.sellerPickupAt().plus(by);
        return extend(terms.cutoffAt().plus(by), terms.window().shift(by), pickup, now);
    }

    public Run cancel(String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new RuleViolation("delivery-runs.reason-required", "A cancellation needs a reason");
        }
        Run next = move(RunStatus.CANCELLED, now);
        return new Run(id, locationId, next.status, terms, originalCutoffAt, extensions, reservedOrders,
                reservedPackages, reason.trim(), reminderSentFor, estimate, milestones.cancelled(now), createdAt, now,
                version);
    }

    public Run startCollecting(Instant now) {
        Run next = move(RunStatus.COLLECTING, now);
        return next.with(next.milestones.collecting(now));
    }

    public Run outForDelivery(Instant now) {
        Run next = move(RunStatus.OUT_FOR_DELIVERY, now);
        return next.with(next.milestones.outForDelivery(now));
    }

    public Run complete(Instant now) {
        Run next = move(RunStatus.COMPLETED, now);
        return next.with(next.milestones.completed(now));
    }

    // ---- orders and capacity -----------------------------------------------------------------------------------

    /** Open for preorders at {@code now}. */
    public boolean acceptsOrders(Instant now) {
        return status.takesOrders() && now.isBefore(terms.cutoffAt());
    }

    /**
     * An announced run opening within {@code earlyHours}: members with early access (S323) can order from then on,
     * everyone else when it opens.
     */
    public boolean acceptsOrdersEarly(Instant now, int earlyHours) {
        return earlyHours > 0 && status == RunStatus.ANNOUNCED && terms.opensAt() != null
                && !now.isBefore(terms.opensAt().minus(Duration.ofHours(earlyHours))) && now.isBefore(terms.cutoffAt());
    }

    /** Whether a member with early access can take a slot now (open, or opening soon, and not full). */
    public boolean canTakeEarly(int packages, Instant now, int earlyHours) {
        return (acceptsOrders(now) || acceptsOrdersEarly(now, earlyHours))
                && terms.capacity().fits(reservedOrders, reservedPackages, packages);
    }

    /** Whether an order of {@code packages} can take a slot now (open and not full). */
    public boolean canTake(int packages, Instant now) {
        return acceptsOrders(now) && terms.capacity().fits(reservedOrders, reservedPackages, packages);
    }

    public boolean isFull(int packages) {
        return !terms.capacity().fits(reservedOrders, reservedPackages, packages);
    }

    /** Takes a slot; callers check {@link #canTake(int, Instant)} first (and hold the row lock). */
    public Run reserve(int packages, Instant now) {
        return reserve(packages, now, 0);
    }

    /** Takes a slot for a member with early access to runs that open within {@code earlyHours}. */
    public Run reserve(int packages, Instant now, int earlyHours) {
        if (packages < 1) {
            throw new RuleViolation("delivery-runs.invalid-packages", "An order has at least one package");
        }
        if (!acceptsOrders(now) && !acceptsOrdersEarly(now, earlyHours)) {
            throw new RuleViolation("delivery-runs.run-closed", "This run no longer takes orders");
        }
        if (isFull(packages)) {
            throw new RuleViolation("delivery-runs.run-full", "This run is full");
        }
        return counts(reservedOrders + 1, reservedPackages + packages, now);
    }

    /** Gives a slot back (order cancelled or moved). */
    public Run release(int packages, Instant now) {
        return counts(Math.max(0, reservedOrders - 1), Math.max(0, reservedPackages - packages), now);
    }

    public boolean minimumMet() {
        return reservedOrders >= terms.minOrders();
    }

    // ---- jobs ----------------------------------------------------------------------------------------------------

    public boolean dueForCutoff(Instant now) {
        return status.takesOrders() && !now.isBefore(terms.cutoffAt());
    }

    public boolean dueForReminder(Instant now, Duration lead) {
        return acceptsOrders(now) && !now.isBefore(terms.cutoffAt().minus(lead))
                && !terms.cutoffAt().equals(reminderSentFor);
    }

    public Run reminderSent(Instant now) {
        return new Run(id, locationId, status, terms, originalCutoffAt, extensions, reservedOrders, reservedPackages,
                cancelReason, terms.cutoffAt(), estimate, milestones, createdAt, now, version);
    }

    public boolean dueToOpen(Instant now) {
        return status == RunStatus.ANNOUNCED && terms.opensAt() != null && !now.isBefore(terms.opensAt())
                && now.isBefore(terms.cutoffAt());
    }

    // ---- staff edits ---------------------------------------------------------------------------------------------

    /**
     * Changes the scheduled terms. DRAFT and ANNOUNCED runs change freely; OPEN and EXTENDED runs keep their fee,
     * currency, cutoff and drop mode (customers already ordered on them; use extend to move the cutoff), and
     * capacity can never drop below what is reserved.
     */
    public Run reschedule(RunTerms changed, Instant now) {
        boolean editable = status == RunStatus.DRAFT || status == RunStatus.ANNOUNCED;
        if (!editable && !status.takesOrders()) {
            throw new RuleViolation("delivery-runs.run-not-editable", "A " + status + " run cannot be edited");
        }
        if (status.takesOrders() && (changed.feeMinor() != terms.feeMinor()
                || !changed.currency().equals(terms.currency()) || !changed.cutoffAt().equals(terms.cutoffAt())
                || changed.dropMode() != terms.dropMode())) {
            throw new RuleViolation("delivery-runs.run-not-editable",
                    "Fee, currency, cutoff and drop mode are fixed once a run is open (extend to move the cutoff)");
        }
        if (!changed.capacity().holds(reservedOrders, reservedPackages)) {
            throw new RuleViolation("delivery-runs.invalid-capacity", "Capacity cannot drop below reserved orders");
        }
        Instant original = editable ? changed.cutoffAt() : originalCutoffAt;
        return new Run(id, locationId, status, changed, original, extensions, reservedOrders, reservedPackages,
                cancelReason, reminderSentFor, estimate, milestones, createdAt, now, version);
    }

    public Run withEstimate(RouteEstimate route, Instant now) {
        return new Run(id, locationId, status, terms, originalCutoffAt, extensions, reservedOrders, reservedPackages,
                cancelReason, reminderSentFor, route, milestones, createdAt, now, version);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private Run move(RunStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new InvalidTransitionException(status.name(), next.name());
        }
        return new Run(id, locationId, next, terms, originalCutoffAt, extensions, reservedOrders, reservedPackages,
                cancelReason, reminderSentFor, estimate, milestones, createdAt, now, version);
    }

    private Run with(RunMilestones m) {
        return new Run(id, locationId, status, terms, originalCutoffAt, extensions, reservedOrders, reservedPackages,
                cancelReason, reminderSentFor, estimate, m, createdAt, updatedAt, version);
    }

    private Run counts(int orders, int packages, Instant now) {
        return new Run(id, locationId, status, terms, originalCutoffAt, extensions, orders, packages, cancelReason,
                reminderSentFor, estimate, milestones, createdAt, now, version);
    }
}
