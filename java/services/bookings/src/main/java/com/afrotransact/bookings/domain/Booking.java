package com.afrotransact.bookings.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A booking and its state machine (the design's booking engine). Every transition validates the current state,
 * records history lines and returns the money instructions (captures, releases, refunds, settlement) that the
 * application carries out against payments-service in the same database transaction.
 *
 * <pre>
 * PENDING_PAYMENT ─authorised─▶ REQUESTED ─confirm─▶ CONFIRMED ─complete─▶ COMPLETED
 *        │ (instant booking) ───────────────────────▶ CONFIRMED ─no-show──▶ NO_SHOW
 *        └─hold expires─▶ EXPIRED      REQUESTED ─decline─▶ DECLINED / ─4 h─▶ EXPIRED
 * REQUESTED|CONFIRMED ─provider proposes─▶ RESCHEDULE_PROPOSED ─customer accepts─▶ CONFIRMED / declines ─▶ CANCELLED_PROVIDER
 * REQUESTED|CONFIRMED ─customer asks─▶ RESCHEDULE_REQUESTED ─provider accepts─▶ CONFIRMED / keeps time ─▶ previous
 * any active ─cancel─▶ CANCELLED_CUSTOMER | CANCELLED_PROVIDER
 * </pre>
 *
 * <p>Long-dated bookings: card authorisations last about 7 days, so when the appointment is further away the balance
 * hold is released right after the deposit capture ({@link #releaseBalanceAhead}) and the balance is charged
 * off-session on the customer's saved card shortly before the appointment ({@link #balanceChargeSubmitted}); the
 * off-session charge is captured on completion. Cancellations and no-shows after the deposit capture release the
 * balance hold (or cancel the open off-session charge).
 */
public final class Booking {

    /** Provider cancellations closer than this to the start cost a fee and a strike. */
    public static final Duration LATE_PROVIDER_CANCELLATION = Duration.ofHours(24);

    private final UUID id;
    private final String reference;
    private final String orgId;
    private final UUID memberId;
    private final String customerId;
    private final CustomerContact customer;
    private final BookedService service;
    private final String location;
    private final String notes;
    private final String channel;
    private Instant startAt;
    private Instant endAt;
    private final int bufferMinutes;
    private Instant proposedStartAt;
    private BookingStatus previousStatus;
    private BookingStatus status;
    private final CancellationPolicy policy;
    private final boolean instant;
    private int reschedules;
    private UUID paymentId;
    private PaymentState paymentState;
    private String clientSecret;
    private String paymentFailure;
    private final Instant holdExpiresAt;
    private Instant requestExpiresAt;
    private long authorizedMinor;
    private long depositCapturedMinor;
    private long balanceCapturedMinor;
    private long refundedMinor;
    private long releasedMinor;
    private long providerFeeMinor;
    private BalanceCharge balance;
    private boolean balanceFlagged;
    private String reason;
    private Party cancelledBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;
    private final List<Activity> newActivities = new ArrayList<>();

    /** Rehydrates a stored booking (persistence adapters only). */
    public Booking(UUID id, String reference, String orgId, UUID memberId, String customerId,
                   CustomerContact customer, BookedService service, String location, String notes, String channel,
                   Instant startAt, Instant endAt, int bufferMinutes, Instant proposedStartAt,
                   BookingStatus previousStatus, BookingStatus status, CancellationPolicy policy, boolean instant,
                   int reschedules, UUID paymentId, PaymentState paymentState, String clientSecret,
                   String paymentFailure, Instant holdExpiresAt, Instant requestExpiresAt, long authorizedMinor,
                   long depositCapturedMinor, long balanceCapturedMinor, long refundedMinor, long releasedMinor,
                   long providerFeeMinor, BalanceCharge balance, String reason, Party cancelledBy, Instant createdAt,
                   Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.orgId = Objects.requireNonNull(orgId, "orgId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.customer = Objects.requireNonNull(customer, "customer");
        this.service = Objects.requireNonNull(service, "service");
        this.location = location;
        this.notes = notes;
        this.channel = channel == null ? "web" : channel;
        this.startAt = Objects.requireNonNull(startAt, "startAt");
        this.endAt = Objects.requireNonNull(endAt, "endAt");
        this.bufferMinutes = bufferMinutes;
        this.proposedStartAt = proposedStartAt;
        this.previousStatus = previousStatus;
        this.status = Objects.requireNonNull(status, "status");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.instant = instant;
        this.reschedules = reschedules;
        this.paymentId = paymentId;
        this.paymentState = Objects.requireNonNull(paymentState, "paymentState");
        this.clientSecret = clientSecret;
        this.paymentFailure = paymentFailure;
        this.holdExpiresAt = holdExpiresAt;
        this.requestExpiresAt = requestExpiresAt;
        this.authorizedMinor = authorizedMinor;
        this.depositCapturedMinor = depositCapturedMinor;
        this.balanceCapturedMinor = balanceCapturedMinor;
        this.refundedMinor = refundedMinor;
        this.releasedMinor = releasedMinor;
        this.providerFeeMinor = providerFeeMinor;
        this.balance = balance == null ? BalanceCharge.onAuthorisation() : balance;
        this.reason = reason;
        this.cancelledBy = cancelledBy;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /**
     * A customer picks a slot: the time is held (PENDING_PAYMENT) until {@code holdExpiresAt} while they authorise
     * the deposit. The policy in force is copied onto the booking.
     */
    public static Booking hold(UUID id, String reference, String orgId, UUID memberId, String customerId,
                               CustomerContact customer, BookedService service, String location, String notes,
                               String channel, Instant start, BookingRules rules, CancellationPolicy policy,
                               Instant holdExpiresAt, Instant now) {
        if (service.mode() == ServiceMode.AT_HOME && (location == null || location.isBlank())) {
            throw new BookingsRuleViolation("bookings.address-required", "Home visits need the customer's address",
                    Map.of("location", "Required for home visits"));
        }
        Booking b = new Booking(id, reference, orgId, memberId, customerId, customer, service,
                location == null || location.isBlank() ? null : location.trim(),
                notes == null || notes.isBlank() ? null : notes.trim(), channel, start,
                start.plus(Duration.ofMinutes(service.durationMinutes())), rules.bufferMinutes(), null, null,
                BookingStatus.PENDING_PAYMENT, policy, rules.instantBooking(), 0, null, PaymentState.PENDING, null,
                null, holdExpiresAt, null, 0, 0, 0, 0, 0, 0, BalanceCharge.onAuthorisation(), null, null, now, now, 0);
        b.log(now, Party.CUSTOMER, "Requested via " + ("app".equals(b.channel) ? "app" : "web")
                + " · slot held while the deposit is authorised");
        return b;
    }

    // ------------------------------------------------------------------ payment

    public void attachPayment(UUID newPaymentId, String newClientSecret) {
        this.paymentId = Objects.requireNonNull(newPaymentId, "paymentId");
        this.clientSecret = newClientSecret;
    }

    /**
     * payments-service authorised the intent. Instant-booking providers confirm automatically (deposit captured);
     * otherwise the request waits for the provider until {@code now + requestSla}. Repeated notices are no-ops.
     */
    public Transition paymentAuthorized(Instant now, Duration requestSla) {
        BookingStatus from = status;
        if (status != BookingStatus.PENDING_PAYMENT) {
            return unchanged();
        }
        paymentState = PaymentState.AUTHORIZED;
        paymentFailure = null;
        authorizedMinor = service.priceMinor();
        log(now, Party.CUSTOMER, service.depositMinor() > 0
                ? money(service.depositMinor()) + " deposit authorised" : "Card authorised", MoneyKind.AUTHORIZE,
                service.priceMinor());
        if (instant) {
            List<MoneyMove> moves = new ArrayList<>();
            captureDeposit(now, moves);
            status = BookingStatus.CONFIRMED;
            log(now, Party.SYSTEM, "Instant booking · confirmed automatically");
            return touch(now, from, moves, false);
        }
        status = BookingStatus.REQUESTED;
        requestExpiresAt = now.plus(requestSla);
        return touch(now, from, List.of(), false);
    }

    /** The last payment attempt failed; the slot stays held so the customer can retry. */
    public Transition paymentFailed(String message, Instant now) {
        if (status != BookingStatus.PENDING_PAYMENT) {
            return unchanged();
        }
        paymentState = PaymentState.FAILED;
        paymentFailure = message == null ? "Payment failed" : message;
        log(now, Party.SYSTEM, "Payment failed · " + paymentFailure);
        return touch(now, status, List.of(), false);
    }

    // ------------------------------------------------------------------ provider actions on requests

    public Transition confirm(Instant now) {
        require(BookingStatus.REQUESTED, "Only new requests can be confirmed");
        List<MoneyMove> moves = new ArrayList<>();
        captureDeposit(now, moves);
        BookingStatus from = status;
        status = BookingStatus.CONFIRMED;
        log(now, Party.PROVIDER, service.depositMinor() > 0 ? "Confirmed · deposit captured" : "Confirmed");
        return touch(now, from, moves, false);
    }

    public Transition decline(String why, Instant now) {
        require(BookingStatus.REQUESTED, "Only new requests can be declined");
        BookingStatus from = status;
        status = BookingStatus.DECLINED;
        reason = blankToNull(why);
        log(now, Party.PROVIDER, "Declined" + (reason == null ? "" : " · “" + reason + "”"));
        return touch(now, from, releaseAll(now, "full refund"), false);
    }

    /** Unanswered requests expire after the SLA and release the deposit hold. */
    public Transition expireRequest(Instant now) {
        require(BookingStatus.REQUESTED, "Only open requests expire");
        if (requestExpiresAt == null || now.isBefore(requestExpiresAt)) {
            throw new BookingsStateConflict("bookings.not-expired", "Request " + reference + " has not expired yet");
        }
        BookingStatus from = status;
        status = BookingStatus.EXPIRED;
        log(now, Party.SYSTEM, "Provider didn’t confirm in time · authorisation released");
        return touch(now, from, releaseAll(now, null), false);
    }

    /** Abandoned checkouts: the slot hold lapses and any authorisation is released. */
    public Transition expireHold(Instant now) {
        require(BookingStatus.PENDING_PAYMENT, "Only unpaid holds expire");
        if (holdExpiresAt == null || now.isBefore(holdExpiresAt)) {
            throw new BookingsStateConflict("bookings.not-expired", "Hold " + reference + " has not expired yet");
        }
        BookingStatus from = status;
        status = BookingStatus.EXPIRED;
        log(now, Party.SYSTEM, "Deposit not authorised in time · slot released");
        List<MoneyMove> moves = paymentId == null ? List.of() : List.of(MoneyMove.release());
        paymentState = PaymentState.RELEASED;
        return touch(now, from, moves, false);
    }

    // ------------------------------------------------------------------ new times

    /** The provider proposes another time (accepting the request in principle: the deposit is captured). */
    public Transition proposeTime(Instant newStart, Instant now) {
        if (status != BookingStatus.REQUESTED && status != BookingStatus.CONFIRMED) {
            throw new BookingsStateConflict("bookings.cannot-move", "This booking can’t be moved");
        }
        List<MoneyMove> moves = new ArrayList<>();
        captureDeposit(now, moves);
        BookingStatus from = status;
        proposedStartAt = newStart;
        previousStatus = BookingStatus.CONFIRMED;
        status = BookingStatus.RESCHEDULE_PROPOSED;
        log(now, Party.PROVIDER, "Proposed a new time · " + newStart);
        return touch(now, from, moves, false);
    }

    /**
     * The customer asks to move. Inside the policy's free-change window it moves straight away when the provider
     * auto-accepts reschedules (or the request isn't confirmed yet); otherwise the provider decides.
     */
    public Transition requestReschedule(Instant newStart, boolean autoAccept, Instant now) {
        if (status != BookingStatus.REQUESTED && status != BookingStatus.CONFIRMED) {
            throw new BookingsStateConflict("bookings.cannot-move", "This booking can’t be moved");
        }
        if (!policy.allowsReschedule(Duration.between(now, startAt), reschedules)) {
            throw new BookingsRuleViolation("bookings.reschedule-closed", "Free changes close "
                    + CancellationPolicy.hours(policy.rescheduleHours()) + " before the appointment"
                    + (reschedules >= policy.maxReschedules() ? " (or you’ve used your reschedules)" : "")
                    + ". Cancel instead, or message the provider.");
        }
        BookingStatus from = status;
        if (autoAccept || status == BookingStatus.REQUESTED) {
            Instant old = startAt;
            moveTo(newStart);
            reschedules++;
            log(now, Party.CUSTOMER, "Moved from " + old + " to " + newStart);
            return touch(now, from, List.of(), true);
        }
        proposedStartAt = newStart;
        previousStatus = status;
        status = BookingStatus.RESCHEDULE_REQUESTED;
        log(now, Party.CUSTOMER, "Asked to move to " + newStart);
        return touch(now, from, List.of(), false);
    }

    /** Accept the pending new time: the provider accepts a customer's request, the customer a provider proposal. */
    public Transition acceptNewTime(Party by, Instant now) {
        requireNewTimeDecision(by);
        BookingStatus from = status;
        List<MoneyMove> moves = new ArrayList<>();
        captureDeposit(now, moves);
        Instant old = startAt;
        moveTo(proposedStartAt);
        proposedStartAt = null;
        previousStatus = null;
        status = BookingStatus.CONFIRMED;
        reschedules++;
        log(now, by, "Accepted new time · moved from " + old + " to " + startAt);
        return touch(now, from, moves, true);
    }

    /**
     * Decline the pending new time. The provider keeps the original time; a customer declining the provider's
     * proposal cancels the booking on the provider's side with a full refund.
     */
    public Transition declineNewTime(Party by, Instant now) {
        requireNewTimeDecision(by);
        BookingStatus from = status;
        proposedStartAt = null;
        if (status == BookingStatus.RESCHEDULE_REQUESTED) {
            status = previousStatus == null ? BookingStatus.CONFIRMED : previousStatus;
            previousStatus = null;
            log(now, Party.PROVIDER, "Kept the original time");
            return touch(now, from, List.of(), false);
        }
        previousStatus = null;
        status = BookingStatus.CANCELLED_PROVIDER;
        cancelledBy = Party.PROVIDER;
        log(now, Party.CUSTOMER, "Declined the new time");
        return touch(now, from, refundAll(now, "full refund — provider couldn’t keep the original time"), false);
    }

    private void requireNewTimeDecision(Party by) {
        if (status == BookingStatus.RESCHEDULE_REQUESTED && by == Party.PROVIDER
                || status == BookingStatus.RESCHEDULE_PROPOSED && by == Party.CUSTOMER) {
            return;
        }
        throw new BookingsStateConflict("bookings.nothing-to-decide", "There is no new time for you to accept or "
                + "decline");
    }

    // ------------------------------------------------------------------ cancellation

    /** What cancelling now would refund, keep or release — exactly what {@link #cancel} will do. */
    public CancellationQuote quoteCancellation(Party by, Instant now, long lateProviderFeeMinor) {
        if (!status.isActive()) {
            throw new BookingsStateConflict("bookings.not-cancellable", "This booking can’t be cancelled");
        }
        long deposit = service.depositMinor();
        boolean nothingCaptured = depositCapturedMinor == 0 && balanceCapturedMinor == 0;
        long authorised = paymentState == PaymentState.AUTHORIZED ? authorizedMinor : 0;
        if (by == Party.PROVIDER) {
            boolean late = status != BookingStatus.REQUESTED && status != BookingStatus.PENDING_PAYMENT
                    && Duration.between(now, startAt).compareTo(LATE_PROVIDER_CANCELLATION) < 0;
            long fee = late ? lateProviderFeeMinor : 0;
            String text = "Full refund to the customer" + (late ? "; late cancellation costs you "
                    + money(fee) + " and a reliability strike" : "");
            return new CancellationQuote(by, 10_000, deposit, nothingCaptured ? 0 : depositCapturedMinor,
                    nothingCaptured, authorised, 0, fee, policy.label(), text, service.currency());
        }
        if (nothingCaptured) {
            return new CancellationQuote(by, 10_000, deposit, 0, true, authorised, 0, 0, policy.label(),
                    "Nothing has been charged yet: the hold on your card is released", service.currency());
        }
        int share = policy.refundShareBps(Duration.between(now, startAt));
        long refund = FeeSchedule.pct(depositCapturedMinor, share);
        long kept = depositCapturedMinor - refund;
        String text = share == 10_000 ? "Full refund of your " + money(depositCapturedMinor) + " deposit"
                : share == 0 ? "The " + money(kept) + " deposit is kept per the " + policy.label() + " policy"
                : money(refund) + " of your deposit comes back (" + share / 100 + "% per the " + policy.label()
                + " policy); " + money(kept) + " is kept";
        return new CancellationQuote(by, share, deposit, refund, false, 0, kept, 0, policy.label(), text,
                service.currency());
    }

    /** Cancels according to {@link #quoteCancellation}; the quote is returned with the transition. */
    public Cancellation cancel(Party by, String why, Instant now, long lateProviderFeeMinor) {
        CancellationQuote quote = quoteCancellation(by, now, lateProviderFeeMinor);
        BookingStatus from = status;
        reason = blankToNull(why);
        cancelledBy = by;
        proposedStartAt = null;
        previousStatus = null;
        status = by == Party.PROVIDER ? BookingStatus.CANCELLED_PROVIDER : BookingStatus.CANCELLED_CUSTOMER;
        log(now, by == Party.SYSTEM ? Party.SYSTEM : by, "Cancelled" + (reason == null ? "" : " · “" + reason + "”"));
        List<MoneyMove> moves = new ArrayList<>();
        if (quote.releaseAuthorisation()) {
            if (paymentId != null) {
                moves.add(MoneyMove.release());
                releasedMinor += quote.releasedMinor();
                log(now, Party.SYSTEM, "Released " + money(quote.releasedMinor()) + " hold on the card",
                        MoneyKind.RELEASE, quote.releasedMinor());
            }
            paymentState = PaymentState.RELEASED;
        } else {
            releaseBalance(now, moves);
            if (quote.refundMinor() > 0) {
                moves.add(MoneyMove.refund(quote.refundMinor()));
                refundedMinor += quote.refundMinor();
                log(now, Party.SYSTEM, "Refunded " + money(quote.refundMinor()) + " · " + quote.explanation(),
                        MoneyKind.REFUND, quote.refundMinor());
            }
            if (quote.keptMinor() > 0) {
                moves.add(MoneyMove.settle());
                log(now, Party.SYSTEM, money(quote.keptMinor()) + " kept per " + policy.label()
                        + " cancellation policy");
            }
        }
        if (quote.providerFeeMinor() > 0) {
            providerFeeMinor += quote.providerFeeMinor();
            log(now, Party.SYSTEM, "Late provider cancellation · " + money(quote.providerFeeMinor())
                    + " fee and 1 reliability strike", MoneyKind.PROVIDER_FEE, quote.providerFeeMinor());
        }
        return new Cancellation(touch(now, from, moves, false), quote);
    }

    /** A cancellation's transition and the quote it applied. */
    public record Cancellation(Transition transition, CancellationQuote quote) {
    }

    // ------------------------------------------------------------------ appointment done

    /** The provider marks the appointment complete: the balance is charged and the settlement hold starts. */
    public Transition complete(Party by, Instant now) {
        require(BookingStatus.CONFIRMED, "Only confirmed bookings can be completed");
        BookingStatus from = status;
        List<MoneyMove> moves = new ArrayList<>();
        captureDeposit(now, moves);
        if (service.balanceMinor() <= 0 || captureBalance(now, moves)) {
            paymentState = PaymentState.CAPTURED;
        }
        moves.add(MoneyMove.settle());
        status = BookingStatus.COMPLETED;
        log(now, by, by == Party.SYSTEM ? "Auto-completed after the appointment" : "Marked complete");
        return touch(now, from, moves, false);
    }

    /** No-show once the start time has passed: deposit kept, or full price when the policy says so. */
    public Transition markNoShow(Instant now) {
        require(BookingStatus.CONFIRMED, "Only confirmed bookings can be marked as no-show");
        if (now.isBefore(startAt)) {
            throw new BookingsStateConflict("bookings.too-early", "You can mark a no-show once the start time has "
                    + "passed");
        }
        BookingStatus from = status;
        List<MoneyMove> moves = new ArrayList<>();
        captureDeposit(now, moves);
        if (policy.noShow() == NoShowRule.FULL && service.balanceMinor() > 0) {
            log(now, Party.PROVIDER, "Marked no-show · full price due per policy");
            if (captureBalance(now, moves)) {
                paymentState = PaymentState.CAPTURED;
            }
        } else {
            log(now, Party.PROVIDER, "Marked no-show · deposit kept, no balance charged");
            releaseBalance(now, moves);
        }
        if (depositCapturedMinor + balanceCapturedMinor > 0) {
            moves.add(MoneyMove.settle());
        }
        status = BookingStatus.NO_SHOW;
        return touch(now, from, moves, false);
    }

    // ------------------------------------------------------------------ long-dated bookings: off-session balance

    /**
     * Right after the deposit capture: when the appointment is beyond the card authorisation window, the balance
     * hold is released and the balance becomes due off-session (charged {@code chargeBefore} the appointment).
     *
     * @return the release instruction, or nothing when the authorisation still covers the appointment
     */
    public List<MoneyMove> releaseBalanceAhead(BalanceChargePolicy chargePolicy, Instant now) {
        long amount = service.balanceMinor();
        if (balance.state() != BalanceState.ON_AUTHORISATION || paymentId == null || amount <= 0
                || depositCapturedMinor == 0 || balanceCapturedMinor > 0 || !status.isActive()
                || status == BookingStatus.PENDING_PAYMENT || status == BookingStatus.REQUESTED
                || !chargePolicy.beyondAuthorisation(startAt, now)) {
            return List.of();
        }
        releasedMinor += amount;
        balance = new BalanceCharge(BalanceState.DUE, null, 0, null, null, null);
        updatedAt = now;
        log(now, Party.SYSTEM, "Appointment is more than " + chargePolicy.authorisationWindow().toDays()
                + " days away · " + money(amount) + " balance hold released; the balance is charged on the saved card "
                + chargePolicy.chargeBefore().toDays() + " days before", MoneyKind.RELEASE_REMAINING, amount);
        return List.of(MoneyMove.releaseRemaining());
    }

    /**
     * payments answered an off-session balance charge (attempt {@code attempt}, key {@code BK-…:offsession:n}).
     *
     * @param paymentStatus AUTHORIZED, REQUIRES_ACTION, FAILED, PROCESSING …
     */
    public Transition balanceChargeSubmitted(int attempt, UUID chargePaymentId, String paymentStatus,
                                             String chargeClientSecret, String failureMessage,
                                             BalanceChargePolicy chargePolicy, Instant now) {
        if (!balance.state().chargeable()) {
            return unchanged();
        }
        balance = new BalanceCharge(balance.state(), chargePaymentId, attempt, null, null, null);
        log(now, Party.SYSTEM, "Balance " + money(service.balanceMinor()) + " charged off-session on the saved card"
                + " (attempt " + attempt + ")");
        return applyBalanceStatus(paymentStatus, chargeClientSecret, failureMessage, chargePolicy, now);
    }

    /** payments refused off-session attempt {@code attempt} (no saved card, …): counts as a declined attempt. */
    public Transition balanceChargeRefused(int attempt, String message, BalanceChargePolicy chargePolicy,
                                           Instant now) {
        if (!balance.state().chargeable()) {
            return unchanged();
        }
        balance = new BalanceCharge(balance.state(), balance.paymentId(), attempt, null, null, null);
        return balanceDeclined(message == null ? "Payment refused" : message, chargePolicy, now);
    }

    /**
     * A later status of the off-session charge (payments event or refresh): authorised after the customer
     * authenticated, declined, waiting for action. Statuses of other (older) charges are ignored.
     */
    public Transition balanceChargeUpdated(UUID chargePaymentId, String paymentStatus, String chargeClientSecret,
                                           String failureMessage, BalanceChargePolicy chargePolicy, Instant now) {
        if (chargePaymentId == null || !chargePaymentId.equals(balance.paymentId())) {
            return unchanged();
        }
        BalanceState state = balance.state();
        boolean authorised = "AUTHORIZED".equals(paymentStatus);
        if (state == BalanceState.RELEASED || state == BalanceState.CAPTURED || state == BalanceState.AUTHORIZED
                || state == BalanceState.NEEDS_ATTENTION && !authorised
                || state == BalanceState.ACTION_REQUIRED && "REQUIRES_ACTION".equals(paymentStatus)
                && chargeClientSecret == null) {
            return unchanged();
        }
        return applyBalanceStatus(paymentStatus, chargeClientSecret, failureMessage, chargePolicy, now);
    }

    /** The job's attempt limit is used up without a declined answer to count (e.g. the limit was lowered). */
    public Transition balanceGiveUp(Instant now) {
        if (!balance.state().chargeable()) {
            return unchanged();
        }
        flagBalance(now, "Balance " + money(service.balanceMinor()) + " not collected after "
                + balance.attempts() + " attempts");
        return touch(now, status, List.of(), false);
    }

    private Transition applyBalanceStatus(String paymentStatus, String chargeClientSecret, String failureMessage,
                                          BalanceChargePolicy chargePolicy, Instant now) {
        long amount = service.balanceMinor();
        List<MoneyMove> moves = new ArrayList<>();
        switch (paymentStatus == null ? "" : paymentStatus) {
            case "AUTHORIZED" -> {
                balance = new BalanceCharge(BalanceState.AUTHORIZED, balance.paymentId(), balance.attempts(), null,
                        null, null);
                log(now, Party.SYSTEM, "Balance " + money(amount) + " authorised on the saved card",
                        MoneyKind.AUTHORIZE, amount);
                if (status == BookingStatus.COMPLETED
                        || status == BookingStatus.NO_SHOW && policy.noShow() == NoShowRule.FULL) {
                    captureBalance(now, moves);
                    paymentState = PaymentState.CAPTURED;
                }
            }
            case "REQUIRES_ACTION" -> {
                balance = new BalanceCharge(BalanceState.ACTION_REQUIRED, balance.paymentId(), balance.attempts(),
                        chargeClientSecret != null ? chargeClientSecret : balance.clientSecret(),
                        failureMessage == null ? "authentication_required" : failureMessage, null);
                log(now, Party.SYSTEM, "The customer’s bank asks them to confirm the " + money(amount)
                        + " balance payment");
            }
            case "FAILED", "CANCELED" -> {
                return balanceDeclined(failureMessage == null ? "Card declined" : failureMessage, chargePolicy, now);
            }
            default -> balance = new BalanceCharge(BalanceState.PROCESSING, balance.paymentId(), balance.attempts(),
                    null, null, null);
        }
        return touch(now, status, moves, false);
    }

    private Transition balanceDeclined(String message, BalanceChargePolicy chargePolicy, Instant now) {
        int attempts = balance.attempts();
        if (attempts >= chargePolicy.maxAttempts() || !status.isActive()) {
            balance = new BalanceCharge(balance.state(), balance.paymentId(), attempts, null, message, null);
            flagBalance(now, "Balance " + money(service.balanceMinor()) + " declined (" + message + ") after "
                    + attempts + (attempts == 1 ? " attempt" : " attempts"));
        } else {
            Instant retryAt = now.plus(chargePolicy.retryDelay());
            balance = new BalanceCharge(BalanceState.FAILED, balance.paymentId(), attempts, null, message, retryAt);
            log(now, Party.SYSTEM, "Balance charge declined (" + message + ") · retried at " + retryAt);
        }
        return touch(now, status, List.of(), false);
    }

    // ------------------------------------------------------------------ helpers

    private void captureDeposit(Instant now, List<MoneyMove> moves) {
        long deposit = service.depositMinor();
        if (deposit > 0 && depositCapturedMinor == 0) {
            moves.add(MoneyMove.capture(MoneyMove.DEPOSIT, deposit, service.balanceMinor() == 0));
            depositCapturedMinor = deposit;
            log(now, Party.SYSTEM, "Deposit " + money(deposit) + " captured", MoneyKind.CAPTURE, deposit);
        }
        if (depositCapturedMinor > 0) {
            paymentState = service.balanceMinor() == 0 ? PaymentState.CAPTURED : PaymentState.DEPOSIT_CAPTURED;
        }
    }

    /** Releases the authorisation when nothing is captured, otherwise refunds the captured deposit in full. */
    private List<MoneyMove> releaseAll(Instant now, String why) {
        if (depositCapturedMinor == 0 && balanceCapturedMinor == 0) {
            long held = paymentState == PaymentState.AUTHORIZED ? authorizedMinor : 0;
            paymentState = PaymentState.RELEASED;
            releasedMinor += held;
            log(now, Party.SYSTEM, "Released " + money(held) + " hold on the card"
                    + (why == null ? "" : " · " + why), MoneyKind.RELEASE, held);
            return paymentId == null ? List.of() : List.of(MoneyMove.release());
        }
        return refundAll(now, why);
    }

    private List<MoneyMove> refundAll(Instant now, String why) {
        if (depositCapturedMinor == 0 && balanceCapturedMinor == 0) {
            return releaseAll(now, why);
        }
        List<MoneyMove> moves = new ArrayList<>();
        releaseBalance(now, moves);
        long refund = depositCapturedMinor - refundedMinor;
        if (refund <= 0) {
            return moves;
        }
        refundedMinor += refund;
        log(now, Party.SYSTEM, "Refunded " + money(refund) + (why == null ? "" : " · " + why), MoneyKind.REFUND,
                refund);
        moves.add(MoneyMove.refund(refund));
        return moves;
    }

    /**
     * Captures the balance on completion (or a full-price no-show): on the original authorisation, or on the
     * authorised off-session charge. Without either (released and not charged yet, declined) nothing is captured
     * and the booking is flagged; a charge still waiting for the customer's authentication stays open and is
     * captured as soon as it is authorised.
     *
     * @return true when the balance was captured
     */
    private boolean captureBalance(Instant now, List<MoneyMove> moves) {
        long amount = service.balanceMinor();
        BalanceState state = balance.state();
        if (state == BalanceState.ON_AUTHORISATION || state == BalanceState.AUTHORIZED) {
            moves.add(MoneyMove.capture(MoneyMove.BALANCE, amount, true));
            balanceCapturedMinor = amount;
            balance = balance.withState(BalanceState.CAPTURED);
            log(now, Party.SYSTEM, "Balance " + money(amount) + " charged"
                    + (state == BalanceState.AUTHORIZED ? " (saved card)" : ""), MoneyKind.CAPTURE, amount);
            return true;
        }
        if (state == BalanceState.ACTION_REQUIRED || state == BalanceState.PROCESSING) {
            log(now, Party.SYSTEM, "Balance " + money(amount) + " not collected yet · waiting for the customer to "
                    + "confirm the payment");
            return false;
        }
        if (state == BalanceState.DUE || state == BalanceState.FAILED) {
            flagBalance(now, "Balance " + money(amount) + " not collected · the saved card couldn’t be charged");
        }
        return false;
    }

    /**
     * Nothing more will be charged (cancellation or no-show after the deposit capture): releases the uncaptured
     * balance hold, or cancels the open off-session charge.
     */
    private void releaseBalance(Instant now, List<MoneyMove> moves) {
        long amount = service.balanceMinor();
        if (amount <= 0 || paymentId == null) {
            return;
        }
        switch (balance.state()) {
            case ON_AUTHORISATION -> {
                if (depositCapturedMinor > 0 && balanceCapturedMinor == 0) {
                    moves.add(MoneyMove.releaseRemaining());
                    releasedMinor += amount;
                    balance = balance.withState(BalanceState.RELEASED);
                    log(now, Party.SYSTEM, "Released the " + money(amount) + " balance hold on the card",
                            MoneyKind.RELEASE_REMAINING, amount);
                }
            }
            case PROCESSING, ACTION_REQUIRED, AUTHORIZED -> {
                moves.add(MoneyMove.cancelBalanceCharge());
                balance = balance.withState(BalanceState.RELEASED);
                log(now, Party.SYSTEM, "Cancelled the " + money(amount) + " balance charge on the saved card",
                        MoneyKind.RELEASE, amount);
            }
            case DUE, FAILED, NEEDS_ATTENTION -> balance = balance.withState(BalanceState.RELEASED);
            default -> {
                // CAPTURED or already RELEASED: nothing to release
            }
        }
    }

    private void flagBalance(Instant now, String why) {
        balance = balance.withState(BalanceState.NEEDS_ATTENTION);
        balanceFlagged = true;
        log(now, Party.SYSTEM, why + " · provider and customer need to settle the balance");
    }

    private void moveTo(Instant newStart) {
        startAt = newStart;
        endAt = newStart.plus(Duration.ofMinutes(service.durationMinutes()));
    }

    private void require(BookingStatus expected, String message) {
        if (status != expected) {
            throw new BookingsStateConflict("bookings.invalid-transition", message + " (booking " + reference
                    + " is " + status.label().toLowerCase(Locale.ROOT) + ")");
        }
    }

    private Transition touch(Instant now, BookingStatus from, List<MoneyMove> moves, boolean moved) {
        updatedAt = now;
        boolean flagged = balanceFlagged;
        balanceFlagged = false;
        return new Transition(from, status, moves, moved, flagged);
    }

    private Transition unchanged() {
        return new Transition(status, status, List.of(), false);
    }

    private void log(Instant at, Party by, String action) {
        newActivities.add(new Activity(at, by, action, null, 0));
    }

    private void log(Instant at, Party by, String action, MoneyKind kind, long amount) {
        newActivities.add(new Activity(at, by, action, kind, amount));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** "$40.00" style amount for history lines (minor units, two decimals). */
    static String money(long minor) {
        return String.format(Locale.ROOT, "$%d.%02d", minor / 100, Math.abs(minor % 100));
    }

    /** End of the time this booking blocks (appointment plus buffer). */
    public Instant blockedUntil() {
        return endAt.plus(Duration.ofMinutes(bufferMinutes));
    }

    /** End of the proposed / requested new time including the buffer, or null. */
    public Instant proposedBlockedUntil() {
        return proposedStartAt == null ? null
                : proposedStartAt.plus(Duration.ofMinutes(service.durationMinutes() + (long) bufferMinutes));
    }

    /** History lines added since the booking was loaded (persisted with it). */
    public List<Activity> drainActivities() {
        List<Activity> out = List.copyOf(newActivities);
        newActivities.clear();
        return out;
    }

    // ------------------------------------------------------------------ accessors

    public UUID id() {
        return id;
    }

    public String reference() {
        return reference;
    }

    public String orgId() {
        return orgId;
    }

    public UUID memberId() {
        return memberId;
    }

    public String customerId() {
        return customerId;
    }

    public CustomerContact customer() {
        return customer;
    }

    public BookedService service() {
        return service;
    }

    public String location() {
        return location;
    }

    public String notes() {
        return notes;
    }

    public String channel() {
        return channel;
    }

    public Instant startAt() {
        return startAt;
    }

    public Instant endAt() {
        return endAt;
    }

    public int bufferMinutes() {
        return bufferMinutes;
    }

    public Instant proposedStartAt() {
        return proposedStartAt;
    }

    public BookingStatus previousStatus() {
        return previousStatus;
    }

    public BookingStatus status() {
        return status;
    }

    public CancellationPolicy policy() {
        return policy;
    }

    public boolean instant() {
        return instant;
    }

    public int reschedules() {
        return reschedules;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public PaymentState paymentState() {
        return paymentState;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String paymentFailure() {
        return paymentFailure;
    }

    public Instant holdExpiresAt() {
        return holdExpiresAt;
    }

    public Instant requestExpiresAt() {
        return requestExpiresAt;
    }

    public long authorizedMinor() {
        return authorizedMinor;
    }

    public long depositCapturedMinor() {
        return depositCapturedMinor;
    }

    public long balanceCapturedMinor() {
        return balanceCapturedMinor;
    }

    public long refundedMinor() {
        return refundedMinor;
    }

    public long releasedMinor() {
        return releasedMinor;
    }

    public long providerFeeMinor() {
        return providerFeeMinor;
    }

    public BalanceCharge balance() {
        return balance;
    }

    /** True for a completed booking the customer may review. */
    public boolean reviewable() {
        return status == BookingStatus.COMPLETED;
    }

    public String reason() {
        return reason;
    }

    public Party cancelledBy() {
        return cancelledBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
