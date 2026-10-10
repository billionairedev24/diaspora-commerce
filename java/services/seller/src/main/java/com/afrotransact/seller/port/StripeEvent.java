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
                          InvoiceSnapshot invoice, SubscriptionSnapshot subscription) {

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
