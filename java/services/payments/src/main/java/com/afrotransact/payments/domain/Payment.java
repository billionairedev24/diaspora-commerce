package com.afrotransact.payments.domain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The payment of one order: one Stripe PaymentIntent (manual capture) that settles to many recipients.
 *
 * @param credit       the part of {@code amount} paid with AfroTransact credit (S320); the PaymentIntent is for
 *                     {@code amount - credit}, and there is none when credit pays everything
 * @param shopperId    the customer whose credit paid it (token subject), null without credit
 * @param multicapture the PaymentIntent allows several partial captures (Stripe multicapture). A single-capture
 *                     intent's first capture is final: Stripe releases whatever is not captured with it
 */
public record Payment(UUID id, String orderId, String customerId, Money amount, PaymentStatus status,
                      String stripePaymentIntentId, String clientSecret, UUID feeScheduleId, Instant placedAt,
                      Money captured, Money refunded, Instant authorizedAt, String failureMessage,
                      Map<String, String> metadata, List<PaymentLine> lines, Instant createdAt, Instant updatedAt,
                      long version, UUID parentPaymentId, boolean multicapture, Money credit,
                      String shopperId) {

    /** Metadata of payments whose captures follow shipments (orders): {@code capture_plan=per_shipment}. */
    public static final String CAPTURE_PLAN = "capture_plan";
    public static final String PER_SHIPMENT = "per_shipment";

    public Payment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(amount, "amount");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        lines = List.copyOf(lines);
    }

    /** A new payment for an order; the lines must add up to the amount. */
    public static Payment create(UUID id, String orderId, String customerId, Money amount, UUID feeScheduleId,
                                 Instant placedAt, Map<String, String> metadata, List<OrderLine> lines, Instant now) {
        if (!amount.isPositive()) {
            throw new PaymentsRuleViolation("payments.invalid-amount", "Amount must be positive",
                    Map.of("amountMinor", "Must be > 0"));
        }
        if (lines.isEmpty()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "At least one order line is required",
                    Map.of("lines", "Required"));
        }
        Money sum = Money.zero(amount.currency());
        List<PaymentLine> pls = new ArrayList<>();
        for (OrderLine l : lines) {
            sum = sum.plus(l.total());
            pls.add(PaymentLine.uncaptured(l));
        }
        if (!sum.equals(amount)) {
            throw new PaymentsRuleViolation("payments.amount-mismatch", "Lines add up to " + sum
                    + " but the amount is " + amount, Map.of("amountMinor", "Must equal Σ items + shipping + tax"));
        }
        return new Payment(id, orderId, customerId, amount, PaymentStatus.REQUIRES_PAYMENT_METHOD, null, null,
                feeScheduleId, placedAt, Money.zero(amount.currency()), Money.zero(amount.currency()), null, null,
                metadata, pls, now, now, 0, null, true, Money.zero(amount.currency()), null);
    }

    /**
     * An off-session follow-up charge of {@code lines} of {@code parent} (same order, fee schedule and placement
     * time) on a saved payment method, used once the parent's authorisation was released or lapsed.
     */
    public static Payment offSession(UUID id, Payment parent, String stripeCustomerId, List<OrderLine> lines,
                                     Map<String, String> metadata, Instant now) {
        Money total = Money.zero(parent.currency());
        for (OrderLine l : lines) {
            total = total.plus(l.total());
        }
        Payment p = create(id, parent.orderId(), stripeCustomerId, total, parent.feeScheduleId(), parent.placedAt(),
                metadata, lines, now);
        return new Payment(p.id, p.orderId, p.customerId, p.amount, p.status, null, null, p.feeScheduleId,
                p.placedAt, p.captured, p.refunded, null, null, p.metadata, p.lines, now, now, 0, parent.id(), true, p.credit, null);
    }

    public String currency() {
        return amount.currency();
    }

    /** What the card pays: the order minus the credit applied. */
    public Money cardAmount() {
        return amount.minus(credit);
    }

    /** Credit pays for the whole order: no PaymentIntent, nothing to capture at Stripe. */
    public boolean paidWithCreditOnly() {
        return credit.isPositive() && credit.equals(amount);
    }

    /** The order is paid partly (or wholly) with credit {@code spent} of {@code shopper}. */
    public Payment withCredit(Money spent, String shopper) {
        return new Payment(id, orderId, customerId, amount, status, stripePaymentIntentId, clientSecret,
                feeScheduleId, placedAt, captured, refunded, authorizedAt, failureMessage, metadata, lines, createdAt,
                updatedAt, version, parentPaymentId, multicapture, spent, shopper);
    }

    /** Credit paid everything, so there is no card to authorise: the payment is authorised from the start. */
    public Payment authorisedByCredit(Instant now) {
        return new Payment(id, orderId, customerId, amount, PaymentStatus.AUTHORIZED, null, null, feeScheduleId,
                placedAt, captured, refunded, now, null, metadata, lines, createdAt, now, version, parentPaymentId,
                multicapture, credit, shopperId);
    }

    /**
     * The credit share of a capture of {@code capture}, given the credit that earlier captures took: proportional to
     * the order, and the rest of the credit with the capture that completes the order, so the shares add up exactly.
     */
    public Money creditShareOf(Money capture, Money creditAlreadyCaptured) {
        if (!credit.isPositive()) {
            return Money.zero(amount.currency());
        }
        Money left = credit.minus(creditAlreadyCaptured);
        if (captured.plus(capture).equals(amount)) {
            return left;
        }
        long share = BigInteger.valueOf(capture.minor()).multiply(BigInteger.valueOf(
                credit.minor())).divide(BigInteger.valueOf(amount.minor())).longValueExact();
        return Money.of(Math.min(share, left.minor()), amount.currency());
    }

    /**
     * The credit part of a refund of {@code refund} (the amount of this refund), given the credit earlier refunds
     * gave back. Proportional to the order and exact on the last: each part goes back to what paid it.
     */
    public Money creditPartOf(Money refund, Money creditAlreadyRefunded) {
        if (!credit.isPositive()) {
            return Money.zero(amount.currency());
        }
        Money left = credit.minus(creditAlreadyRefunded);
        if (refunded.plus(refund).equals(amount)) {
            return left;
        }
        long share = BigInteger.valueOf(refund.minor()).multiply(BigInteger.valueOf(
                credit.minor())).divide(BigInteger.valueOf(amount.minor())).longValueExact();
        return Money.of(Math.min(share, left.minor()), amount.currency());
    }

    /** A follow-up off-session charge (has a parent payment of the same order). */
    public boolean isOffSession() {
        return parentPaymentId != null;
    }

    /**
     * Prefix of the Stripe idempotency keys of this payment: the order id for the order's primary payment,
     * {@code <orderId>:os:<paymentId>} for an off-session charge so its keys never collide with the parent's.
     */
    public String stripeKeyPrefix() {
        return parentPaymentId == null ? orderId : orderId + ":os:" + id;
    }

    /** Authorised but never captured and no longer capturable (final capture, release or cancellation). */
    public Money released() {
        return status.isFinal() ? amount.minus(captured) : Money.zero(amount.currency());
    }

    /**
     * Releases the authorisation that was not captured: after a partial (deposit) capture the payment becomes
     * CAPTURED for what was captured; without captures it is cancelled. Uncaptured lines stay uncaptured and can
     * later be charged off-session.
     */
    public Payment releaseRemaining(Instant now) {
        if (status.isFinal()) {
            return this;
        }
        if (captured.isZero()) {
            return cancel(now);
        }
        if (status != PaymentStatus.PARTIALLY_CAPTURED) {
            throw new PaymentsStateConflict("payments.not-releasable", "Payment of order " + orderId + " is "
                    + status + "; only authorised payments can release their remaining authorisation");
        }
        return new Payment(id, orderId, customerId, amount, PaymentStatus.CAPTURED, stripePaymentIntentId,
                clientSecret, feeScheduleId, placedAt, captured, refunded, authorizedAt, failureMessage, metadata,
                lines, createdAt, now, version, parentPaymentId, multicapture, credit, shopperId);
    }

    /** Off-session confirmation needs the customer (3-D Secure / SCA): the intent waits for them on-session. */
    public Payment awaitingCustomer(String reason, Instant now) {
        return new Payment(id, orderId, customerId, amount, PaymentStatus.REQUIRES_ACTION, stripePaymentIntentId,
                clientSecret, feeScheduleId, placedAt, captured, refunded, authorizedAt, reason, metadata, lines,
                createdAt, now, version, parentPaymentId, multicapture, credit, shopperId);
    }

    public Optional<PaymentLine> line(String lineId) {
        return lines.stream().filter(l -> l.lineId().equals(lineId)).findFirst();
    }

    public Money uncaptured() {
        return amount.minus(captured);
    }

    public Payment withIntent(String intentId, String secret, PaymentStatus newStatus, Instant now) {
        return withIntent(intentId, secret, newStatus, multicapture, now);
    }

    /** The PaymentIntent was created; {@code multi} says whether it allows several partial captures. */
    public Payment withIntent(String intentId, String secret, PaymentStatus newStatus, boolean multi, Instant now) {
        return new Payment(id, orderId, customerId, amount, newStatus, intentId, secret, feeScheduleId, placedAt,
                captured, refunded, authorizedAt, failureMessage, metadata, lines, createdAt, now, version,
                parentPaymentId, multi, credit, shopperId);
    }

    /** Captures follow the order's shipments ({@link #CAPTURE_PLAN} = {@link #PER_SHIPMENT}, set by orders). */
    public boolean capturedPerShipment() {
        return PER_SHIPMENT.equals(metadata.get(CAPTURE_PLAN));
    }

    /**
     * Applies a status reported by Stripe. Captures are tracked by AfroTransact, so Stripe's {@code succeeded} after a
     * capture we recorded keeps our CAPTURED / PARTIALLY_CAPTURED view; a status never moves back from a final one,
     * and an authorised payment never moves back to an earlier state (see {@link #isStaleReport}).
     */
    public Payment syncStatus(PaymentStatus reported, String failure, Instant now) {
        if (status.isFinal() || reported == status || isStaleReport(reported)) {
            return this;
        }
        PaymentStatus next = reported;
        Instant authorised = authorizedAt;
        if (reported == PaymentStatus.AUTHORIZED || reported == PaymentStatus.CAPTURED) {
            authorised = authorised == null ? now : authorised;
            if (status == PaymentStatus.PARTIALLY_CAPTURED) {
                next = status;
            } else if (reported == PaymentStatus.CAPTURED && captured.lessThan(amount)) {
                next = captured.isZero() ? PaymentStatus.AUTHORIZED : PaymentStatus.PARTIALLY_CAPTURED;
            }
        }
        String failureText = reported == PaymentStatus.FAILED ? failure : null;
        return new Payment(id, orderId, customerId, amount, next, stripePaymentIntentId, clientSecret, feeScheduleId,
                placedAt, captured, refunded, authorised, failureText, metadata, lines, createdAt, now, version,
                parentPaymentId, multicapture, credit, shopperId);
    }

    /**
     * True when {@code reported} would move an authorised payment (AUTHORIZED or PARTIALLY_CAPTURED) back to an earlier
     * state: FAILED, REQUIRES_ACTION, REQUIRES_PAYMENT_METHOD, REQUIRES_CONFIRMATION or PROCESSING. Stripe never takes
     * a {@code requires_capture} intent back there, so such a report is an older event delivered late (webhooks are
     * not ordered) and must not downgrade the payment. Capture and cancellation still apply.
     */
    public boolean isStaleReport(PaymentStatus reported) {
        return status.isCapturable() && reported != PaymentStatus.AUTHORIZED && reported != PaymentStatus.CAPTURED
                && reported != PaymentStatus.CANCELED && reported != PaymentStatus.PARTIALLY_CAPTURED;
    }

    /** Records a capture of {@code lineIds}; the payment becomes CAPTURED when everything (or the final one) is. */
    public Payment capture(UUID captureId, Money capturedAmount, List<LineSplit> splits, boolean finalCapture,
                           Instant now) {
        if (!status.isCapturable()) {
            throw new PaymentsStateConflict("payments.not-capturable", "Payment of order " + orderId + " is "
                    + status + "; only AUTHORIZED or PARTIALLY_CAPTURED payments can be captured");
        }
        Money total = captured.plus(capturedAmount);
        if (total.greaterThan(amount)) {
            throw new PaymentsRuleViolation("payments.capture-exceeds-authorised", "Capturing " + capturedAmount
                    + " would exceed the authorised " + amount);
        }
        List<PaymentLine> updated = new ArrayList<>();
        for (PaymentLine pl : lines) {
            LineSplit split = splits.stream().filter(s -> s.lineId().equals(pl.lineId())).findFirst().orElse(null);
            updated.add(split == null ? pl : pl.captured(captureId, split));
        }
        PaymentStatus next = finalCapture || total.equals(amount) ? PaymentStatus.CAPTURED
                : PaymentStatus.PARTIALLY_CAPTURED;
        return new Payment(id, orderId, customerId, amount, next, stripePaymentIntentId, clientSecret, feeScheduleId,
                placedAt, total, refunded, authorizedAt, failureMessage, metadata, updated, createdAt, now, version,
                parentPaymentId, multicapture, credit, shopperId);
    }

    public Payment cancel(Instant now) {
        if (status == PaymentStatus.CANCELED) {
            return this;
        }
        if (!captured.isZero()) {
            throw new PaymentsStateConflict("payments.already-captured", "Payment of order " + orderId
                    + " has captures; release the remaining authorisation (POST /v1/payments/{id}/release) or "
                    + "refund instead of cancelling");
        }
        return new Payment(id, orderId, customerId, amount, PaymentStatus.CANCELED, stripePaymentIntentId,
                clientSecret, feeScheduleId, placedAt, captured, refunded, authorizedAt, failureMessage, metadata,
                lines, createdAt, now, version, parentPaymentId, multicapture, credit, shopperId);
    }

    public Payment refund(Refund refund, Instant now) {
        List<PaymentLine> updated = new ArrayList<>();
        for (PaymentLine pl : lines) {
            PaymentLine next = pl;
            for (RefundLine rl : refund.lines()) {
                if (rl.lineId().equals(pl.lineId())) {
                    next = next.refunded(rl);
                }
            }
            updated.add(next);
        }
        return new Payment(id, orderId, customerId, amount, status, stripePaymentIntentId, clientSecret,
                feeScheduleId, placedAt, captured, refunded.plus(refund.amount()), authorizedAt, failureMessage,
                metadata, updated, createdAt, now, version, parentPaymentId, multicapture, credit, shopperId);
    }
}
