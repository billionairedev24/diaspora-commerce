package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.domain.GiftCard;
import com.afrotransact.payments.domain.Money;

/**
 * Gift cards (S321). A customer buys a card with the normal card payment (Stripe Elements on AfroTransact's own page),
 * chooses the recipient's email, a message and the day it is sent; the code is generated and emailed on that day and
 * redeemed once into the recipient's AfroTransact credit. Codes are stored as keyed hashes and guesses are rate
 * limited. Unredeemed cards are a liability, not revenue. Credit never buys a gift card (that would move credit to
 * someone else), and a redeemed card cannot be refunded to a card.
 */
public interface GiftCardService {

    /** The limits, preset amounts and terms for the purchase page (customer or staff). */
    Config config();

    /**
     * Starts a purchase: creates the pending card and the PaymentIntent the customer confirms with Stripe Elements
     * (customer). Idempotent per Idempotency-Key.
     */
    Purchase startPurchase(PurchaseCommand command, String idempotencyKey);

    /**
     * After the customer confirmed: reads the payment from Stripe, captures it and issues the card (customer, owner of
     * the purchase). The webhook does the same, so either order works.
     */
    Purchase confirmPurchase(UUID id);

    /** The cards the signed-in customer bought, newest first; never with a code (customer). */
    List<GiftCard> myPurchases();

    /**
     * Redeems the code into the signed-in customer's credit, once (customer). Failed guesses are counted per customer
     * and per network address; over the limit the answer is 429 with Retry-After.
     *
     * @param clientAddress the caller's network address as the gateway saw it, or null
     */
    Redemption redeem(String code, String clientAddress);

    /** Staff issue a card (ledger:adjust): goodwill or a promotion, with a reason; active at once. */
    GiftCard issue(IssueCommand command);

    /**
     * Staff void an unredeemed card with a reason (ledger:adjust). A purchased card is refunded to the purchaser's
     * card; one staff issued just ends. A redeemed card is credit now and cannot be voided.
     */
    GiftCard voidCard(UUID id, String reason);

    /** Staff send the email again with a new code; the old one stops working (ledger:adjust). */
    GiftCard resend(UUID id, String reason);

    GiftCard get(UUID id);

    /** Staff lookup by card id, last four of the code, recipient, sender or purchaser (payments:read). */
    List<GiftCard> search(String query, GiftCard.Status status, int limit);

    /** Stripe reported on a PaymentIntent (webhook inbox); settles the purchase when it belongs to a gift card. */
    void onPaymentIntent(String paymentIntentId);

    /** Generates the code of every card whose send date has come and hands the email to notifications. */
    int deliverDue();

    /**
     * Drops the plain codes from delivery events that were already relayed, so the database keeps only the hash
     * (S321: codes are stored hashed). Safe to repeat.
     */
    int scrubDeliveredCodes();

    /**
     * @param presetsMinor amounts offered as buttons
     * @param terms        staff terms for the customer's language, or null (the page shows its built-in terms)
     */
    record Config(Money min, Money max, List<Long> presetsMinor, Map<String, String> terms) {
    }

    /**
     * @param sendAt null sends it now
     * @param locale the language of the email
     */
    record PurchaseCommand(long amountMinor, String recipientEmail, String recipientName, String senderName,
                           String message, Instant sendAt, String locale) {
    }

    /** A purchase and what the page needs to pay for it. */
    record Purchase(GiftCard card, String clientSecret) {
    }

    record IssueCommand(long amountMinor, String recipientEmail, String recipientName, String message,
                        Instant sendAt, String locale, String reason) {
    }

    record Redemption(GiftCard card, Money redeemed, Money balance) {
    }
}
