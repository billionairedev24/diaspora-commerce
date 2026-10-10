package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** The payment of one order: one Stripe PaymentIntent (manual capture) that settles to many recipients. */
public record Payment(UUID id, String orderId, String customerId, Money amount, PaymentStatus status,
                      String stripePaymentIntentId, String clientSecret, UUID feeScheduleId, Instant placedAt,
                      Money captured, Money refunded, Instant authorizedAt, String failureMessage,
                      Map<String, String> metadata, List<PaymentLine> lines, Instant createdAt, Instant updatedAt,
                      long version) {

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
                metadata, pls, now, now, 0);
    }

    public String currency() {
        return amount.currency();
    }

    public Optional<PaymentLine> line(String lineId) {
        return lines.stream().filter(l -> l.lineId().equals(lineId)).findFirst();
    }

    public Money uncaptured() {
        return amount.minus(captured);
    }

    public Payment withIntent(String intentId, String secret, PaymentStatus newStatus, Instant now) {
        return new Payment(id, orderId, customerId, amount, newStatus, intentId, secret, feeScheduleId, placedAt,
                captured, refunded, authorizedAt, failureMessage, metadata, lines, createdAt, now, version);
    }

    /**
     * Applies a status reported by Stripe. Captures are tracked by AfroTransact, so Stripe's {@code succeeded} after a
     * capture we recorded keeps our CAPTURED / PARTIALLY_CAPTURED view; a status never moves back from a final one.
     */
    public Payment syncStatus(PaymentStatus reported, String failure, Instant now) {
        if (status.isFinal() || reported == status) {
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
                placedAt, captured, refunded, authorised, failureText, metadata, lines, createdAt, now, version);
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
                placedAt, total, refunded, authorizedAt, failureMessage, metadata, updated, createdAt, now, version);
    }

    public Payment cancel(Instant now) {
        if (status == PaymentStatus.CANCELED) {
            return this;
        }
        if (!captured.isZero()) {
            throw new PaymentsStateConflict("payments.already-captured", "Payment of order " + orderId
                    + " has captures; refund instead of cancelling");
        }
        return new Payment(id, orderId, customerId, amount, PaymentStatus.CANCELED, stripePaymentIntentId,
                clientSecret, feeScheduleId, placedAt, captured, refunded, authorizedAt, failureMessage, metadata,
                lines, createdAt, now, version);
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
                metadata, updated, createdAt, now, version);
    }
}
