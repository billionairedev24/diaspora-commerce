package com.afrotransact.seller.port;

/**
 * Plain Stripe Payments for plan fees: a customer per organisation, SetupIntents to save a card or US bank account, and
 * off-session PaymentIntents for what credits, coupons and (if the seller chose it) earnings did not cover. No Stripe
 * Billing objects (subscriptions, invoices, prices, meters) are used: periods, invoices and dunning are ours.
 */
public interface PlanPaymentsGateway {

    /** The Stripe customer that owns the organisation's saved payment methods; idempotent per organisation. */
    String ensureCustomer(String orgId, String name, String email);

    /** A SetupIntent for saving a card or bank account for off-session plan charges (client confirms it). */
    SetupIntentSecret createSetupIntent(String customerId, String idempotencyKey);

    /**
     * Makes sure the payment method belongs to the customer (a method the browser created without a SetupIntent is not
     * attached yet). Attaching an already attached method is a no-op.
     */
    void attachPaymentMethod(String customerId, String paymentMethodId);

    /** Detaches a saved payment method (best effort; removing it locally is what matters). */
    void detachPaymentMethod(String paymentMethodId);

    /**
     * Charges {@code amountCents} to the saved payment method off-session. Idempotent per {@code idempotencyKey}
     * (invoice id and attempt), so a retried call returns the first attempt's outcome. A declined card is a
     * {@link Status#FAILED} result, not an exception; only transport and configuration problems throw.
     */
    Charge charge(String customerId, String paymentMethodId, long amountCents, String currency, String invoiceId,
                  String description, String idempotencyKey);

    /** A SetupIntent's id and the client secret the browser or app confirms it with. */
    record SetupIntentSecret(String id, String clientSecret) {
    }

    /** SUCCEEDED, PROCESSING (bank debits settle later; a webhook reports the end), REQUIRES_ACTION or FAILED. */
    enum Status { SUCCEEDED, PROCESSING, REQUIRES_ACTION, FAILED }

    /**
     * One attempt.
     *
     * @param reference      the PaymentIntent id
     * @param failureCode    Stripe's decline / error code when FAILED
     * @param failureMessage a sentence for the seller
     */
    record Charge(Status status, String reference, String failureCode, String failureMessage) {
    }
}
