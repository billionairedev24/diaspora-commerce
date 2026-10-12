package com.afrotransact.seller.port;

import java.time.Instant;

import com.afrotransact.seller.domain.RequirementsSnapshot;

/**
 * A signature-verified Stripe webhook event, already parsed. Only the field matching {@code type} is set.
 *
 * @param created event creation time (epoch seconds); used to apply account snapshots in order
 */
public record StripeEvent(String id, String type, long created, String accountId, RequirementsSnapshot account,
                          Capability capability, Person person, ExternalAccount externalAccount, Payout payout,
                          InvoiceSnapshot invoice, SubscriptionSnapshot subscription, CreditNote creditNote) {

    /** Without a credit note (every event but credit_note.*). */
    public StripeEvent(String id, String type, long created, String accountId, RequirementsSnapshot account,
                       Capability capability, Person person, ExternalAccount externalAccount, Payout payout,
                       InvoiceSnapshot invoice, SubscriptionSnapshot subscription) {
        this(id, type, created, accountId, account, capability, person, externalAccount, payout, invoice, subscription,
                null);
    }

    /**
     * credit_note.created (S298): a refund of a plan invoice. {@code outOfBandAmount} is the part refunded outside
     * Stripe, which is how a fee paid from earnings is refunded (released back to the seller's next transfer).
     */
    public record CreditNote(String id, String invoiceId, String customerId, long total, long outOfBandAmount) {
    }

    /** capability.updated */
    public record Capability(String id, String status) {
    }

    /** person.created / person.updated */
    public record Person(String id, String verificationStatus, boolean representative) {
    }

    /** account.external_account.updated */
    public record ExternalAccount(String id, String bankName, String last4, String status) {
    }

    /** payout.failed */
    public record Payout(String id, long amount, String currency, String failureCode, String failureMessage,
                         Instant arrivalDate) {
    }
}
