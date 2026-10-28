package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A gift card: bought with a card (or issued by staff), delivered to the recipient by email on the send date and
 * redeemed once into the recipient's credit. The code is stored only as a keyed hash.
 *
 * @param codeHash        HMAC of the normalised code; null until the code is generated for delivery
 * @param codeLast4       last four characters of the code, for staff lookups and the purchaser's list
 * @param purchaserId     the customer who bought it; null when staff issued it
 * @param paymentIntentId the Stripe PaymentIntent of the purchase
 * @param issuedBy        the staff member who issued it, with {@code issueReason}
 */
public record GiftCard(UUID id, String codeHash, String codeLast4, Money amount, Origin origin, Status status,
                       String purchaserId, String senderName, String recipientEmail, String recipientName,
                       String message, String locale, Instant sendAt, Delivery delivery, int deliveryCount,
                       Instant sentAt, String paymentIntentId, String chargeId, String issueReason, String issuedBy,
                       String redeemedBy, Instant redeemedAt, String voidReason, String voidedBy, Instant voidedAt,
                       boolean refundedToCard, Instant createdAt, Instant updatedAt, long version) {

    /** How the card came to be. */
    public enum Origin { PURCHASE, STAFF }

    /** Where the card is in its life. Unredeemed ACTIVE cards are a liability, not revenue. */
    public enum Status {
        PENDING_PAYMENT, ACTIVE, REDEEMED, VOID, PAYMENT_FAILED;

        public boolean liability() {
            return this == ACTIVE;
        }
    }

    /** Email delivery. */
    public enum Delivery { SCHEDULED, SENT }

    /** A purchase waiting for its payment. */
    public static GiftCard purchase(UUID id, Money amount, String purchaserId, String senderName,
                                    String recipientEmail, String recipientName, String message, String locale,
                                    Instant sendAt, String paymentIntentId, Instant now) {
        return new GiftCard(id, null, null, amount, Origin.PURCHASE, Status.PENDING_PAYMENT, purchaserId,
                trim(senderName), recipientEmail.trim(), trim(recipientName), trim(message), locale, sendAt,
                Delivery.SCHEDULED, 0, null, paymentIntentId, null, null, null, null, null, null, null, null, false,
                now, now, 0);
    }

    /** A card staff issue (goodwill, promotion); active at once. */
    public static GiftCard issuedByStaff(UUID id, Money amount, String staff, String reason, String recipientEmail,
                                         String recipientName, String message, String locale, Instant sendAt,
                                         Instant now) {
        if (reason == null || reason.trim().length() < CreditAdjustment.MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required",
                    "Say why (at least " + CreditAdjustment.MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        return new GiftCard(id, null, null, amount, Origin.STAFF, Status.ACTIVE, null, null, recipientEmail.trim(),
                trim(recipientName), trim(message), locale, sendAt, Delivery.SCHEDULED, 0, null, null, null,
                reason.trim(), staff, null, null, null, null, null, false, now, now, 0);
    }

    public GiftCard paid(String chargeId, Instant now) {
        if (status == Status.ACTIVE) {
            return this;
        }
        if (status != Status.PENDING_PAYMENT && status != Status.PAYMENT_FAILED) {
            throw new PaymentsStateConflict("payments.gift-card-state", "Gift card is " + status);
        }
        return copy(codeHash, codeLast4, Status.ACTIVE, Delivery.SCHEDULED, deliveryCount, sentAt, chargeId,
                redeemedBy, redeemedAt, voidReason, voidedBy, voidedAt, refundedToCard, now);
    }

    public GiftCard paymentFailed(Instant now) {
        if (status != Status.PENDING_PAYMENT) {
            return this;
        }
        return copy(codeHash, codeLast4, Status.PAYMENT_FAILED, delivery, deliveryCount, sentAt, chargeId, null,
                null, null, null, null, false, now);
    }

    /** The code was generated and handed to the notifications; a resend generates a new one and retires the old. */
    public GiftCard delivered(String newHash, String last4, Instant now) {
        if (status != Status.ACTIVE) {
            throw new PaymentsStateConflict("payments.gift-card-state", "Only an active gift card is delivered; "
                    + "this one is " + status);
        }
        return copy(newHash, last4, status, Delivery.SENT, deliveryCount + 1, now, chargeId, redeemedBy, redeemedAt,
                voidReason, voidedBy, voidedAt, refundedToCard, now);
    }

    public GiftCard redeemed(String customerId, Instant now) {
        if (status != Status.ACTIVE) {
            throw new PaymentsStateConflict(statusCode(), "Gift card is " + status);
        }
        return copy(codeHash, codeLast4, Status.REDEEMED, delivery, deliveryCount, sentAt, chargeId, customerId, now,
                null, null, null, refundedToCard, now);
    }

    public GiftCard voided(String staff, String reason, boolean refunded, Instant now) {
        if (status == Status.VOID) {
            return this;
        }
        if (status == Status.REDEEMED) {
            throw new PaymentsStateConflict("payments.gift-card-redeemed",
                    "A redeemed gift card is credit now and cannot be voided");
        }
        if (status != Status.ACTIVE) {
            throw new PaymentsStateConflict("payments.gift-card-state", "Gift card is " + status);
        }
        if (reason == null || reason.trim().length() < CreditAdjustment.MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required",
                    "Say why (at least " + CreditAdjustment.MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        GiftCard v = copy(codeHash, codeLast4, Status.VOID, delivery, deliveryCount, sentAt, chargeId, redeemedBy,
                redeemedAt, reason.trim(), staff, now, refunded, now);
        return v;
    }

    /** True while a redeem may still succeed. */
    public boolean redeemable() {
        return status == Status.ACTIVE && delivery == Delivery.SENT;
    }

    private String statusCode() {
        return switch (status) {
            case REDEEMED -> "payments.gift-card-redeemed";
            case VOID -> "payments.gift-card-void";
            default -> "payments.gift-card-state";
        };
    }

    private GiftCard copy(String hash, String last4, Status newStatus, Delivery newDelivery, int count, Instant sent,
                          String charge, String redeemer, Instant redeemTime, String voidWhy, String voidBy,
                          Instant voidTime, boolean refunded, Instant now) {
        return new GiftCard(id, hash, last4, amount, origin, newStatus, purchaserId, senderName, recipientEmail,
                recipientName, message, locale, sendAt, newDelivery, count, sent, paymentIntentId, charge,
                issueReason, issuedBy, redeemer, redeemTime, voidWhy, voidBy, voidTime, refunded, createdAt, now,
                version);
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
