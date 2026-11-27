package com.afrotransact.seller.port;

/**
 * Plain Stripe Payments for plan fees: a customer per organisation, SetupIntents to save a card or a US bank account
 * (ACH: Financial Connections instant verification with a microdeposit fallback, online mandate), and off-session
 * PaymentIntents ({@code metadata.purpose=plan_fee}) for what credits, coupons and (if the seller chose it) earnings did
 * not cover. No Stripe Billing objects (subscriptions, invoices, prices, meters) are used: periods, invoices and dunning
 * are ours. The outcome of a bank debit (days later), a 3-D Secure confirmation, refunds and disputes arrive on the
 * platform webhook and reach seller as payments.plan_fee.stripe_event.
 */
public interface PlanPaymentsGateway {

    /** The Stripe customer that owns the organisation's saved payment methods; idempotent per organisation. */
    String ensureCustomer(String orgId, String name, String email);

    /**
     * A SetupIntent for saving a card or US bank account for off-session plan charges (client confirms it with the
     * Payment Element, which shows the ACH mandate); {@code metadata.purpose=seller_billing}.
     */
    SetupIntentSecret createSetupIntent(String customerId, String idempotencyKey);

    /**
     * Makes sure the payment method belongs to the customer (a method the browser created without a SetupIntent is not
     * attached yet). Attaching an already attached method is a no-op.
     */
    void attachPaymentMethod(String customerId, String paymentMethodId);

    /** Detaches a saved payment method (best effort; removing it locally is what matters). */
    void detachPaymentMethod(String paymentMethodId);

    /**
     * Charges {@code amountCents} to the saved card or bank account off-session. Idempotent per {@code idempotencyKey}
     * (invoice id and attempt), so a retried call returns the first attempt's outcome. A declined card is a
     * {@link Status#FAILED} result, not an exception; only transport and configuration problems throw. A bank debit is
     * {@link Status#PROCESSING} (it settles in up to 4 business days).
     */
    Charge charge(String customerId, String paymentMethodId, long amountCents, String currency, String invoiceId,
                  String description, String idempotencyKey);

    /**
     * Where a plan-fee PaymentIntent stands now (retrieved from Stripe), with its client secret for an on-session
     * confirmation (3-D Secure). Used to confirm a charge that needs action and as a backstop for a lost event.
     */
    Charge paymentStatus(String paymentIntentId);

    /**
     * Refunds {@code amountCents} of a plan-fee PaymentIntent (partial or full). Idempotent per {@code idempotencyKey}.
     * A refusal (already refunded, more than charged) throws a rejected {@link GatewayException}.
     */
    RefundResult refund(String paymentIntentId, long amountCents, String reason, String idempotencyKey);

    /**
     * A confirmed SetupIntent: {@code succeeded} (a card, or a bank account verified instantly with Financial
     * Connections) or {@code requires_action} (the bank account waits for microdeposits), with its ACH mandate.
     */
    SetupResult setupIntent(String setupIntentId);

    /** status, the payment method it saved and, for a US bank account, the mandate id. */
    record SetupResult(String status, String paymentMethodId, String mandateId) {
    }

    /** A SetupIntent's id and the client secret the browser or app confirms it with. */
    record SetupIntentSecret(String id, String clientSecret) {
    }

    /** SUCCEEDED, PROCESSING (a bank debit in flight), REQUIRES_ACTION (3-D Secure) or FAILED. */
    enum Status { SUCCEEDED, PROCESSING, REQUIRES_ACTION, FAILED }

    /**
     * One attempt.
     *
     * @param reference      the PaymentIntent id
     * @param failureCode    Stripe's decline / error code when FAILED
     * @param failureMessage a sentence for the seller
     * @param clientSecret   the PaymentIntent's client secret (for Stripe.js confirmCardPayment); null when not read
     * @param paymentMethodId the payment method the intent was (last) tried with; null when not read
     */
    record Charge(Status status, String reference, String failureCode, String failureMessage, String clientSecret,
                  String paymentMethodId) {

        public Charge(Status status, String reference, String failureCode, String failureMessage) {
            this(status, reference, failureCode, failureMessage, null, null);
        }
    }

    /** A Stripe refund: {@code status} pending, succeeded, failed, canceled or requires_action. */
    record RefundResult(String id, String status, long amountCents) {
    }
}
