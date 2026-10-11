package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.Money;

/**
 * Outbound port to Stripe (PaymentIntents with manual capture, Connect transfers and payouts, balance
 * transactions). Every mutating call takes an idempotency key that callers derive from business ids
 * ({@code <orderId>:capture:<shipmentId>} …) so retries never move money twice. There is deliberately no hosted
 * payment page (Checkout Session / Payment Link): customers always pay on AfroTransact's own pages with Stripe
 * Elements, confirming the order's PaymentIntent (docs/engineering/payments-idempotency.md).
 */
public interface StripeGateway {

    /** PaymentIntent with capture_method=manual, multicapture when available, automatic payment methods. */
    IntentSnapshot createIntent(CreateIntent request, String idempotencyKey);

    /** Reads the current status (confirmation / 3-D Secure / authorisation). */
    IntentSnapshot retrieveIntent(String intentId);

    /** Captures {@code amount} (amount_to_capture); {@code finalCapture=false} keeps the rest capturable. */
    IntentSnapshot capture(String intentId, Money amount, boolean finalCapture, Map<String, String> metadata,
                           String idempotencyKey);

    IntentSnapshot cancel(String intentId, String idempotencyKey);

    /**
     * Releases what is still authorised but uncaptured after one or more partial captures. Stripe models this as
     * cancelling a {@code requires_capture} PaymentIntent that already has captures (multicapture): the remainder
     * is released and the intent ends {@code succeeded} for the captured amount. (The alternative, a final capture,
     * needs a positive amount to capture.)
     */
    IntentSnapshot releaseUncaptured(String intentId, String idempotencyKey);

    /**
     * Creates and confirms a manual-capture PaymentIntent off-session on a saved payment method of the customer.
     * Never throws for card errors that leave an intent behind (e.g. {@code authentication_required},
     * {@code card_declined}): they come back as the attempt's error with the intent's state.
     */
    OffSessionAttempt createOffSessionIntent(OffSessionIntent request, String idempotencyKey);

    /**
     * Account debit: moves {@code amount} from a connected account's Stripe balance to the platform balance
     * (a Charge whose source is the connected account, {@code acct_…}). Used for fees a provider owes that are not
     * tied to one transfer.
     */
    AccountDebitSnapshot debitConnectedAccount(String connectedAccount, Money amount, String description,
                                               Map<String, String> metadata, String idempotencyKey);

    /** Partial refund of a PaymentIntent; transfer reversals are done separately by AfroTransact. */
    RefundSnapshot refund(String intentId, Money amount, Map<String, String> metadata, String idempotencyKey);

    /** Balance transactions created by a charge (fee and net of each capture). */
    List<BalanceTxn> chargeBalanceTransactions(String chargeId);

    /** Connect transfer to a connected account with {@code transfer_group} = order id. */
    TransferSnapshot createTransfer(Money amount, String destinationAccount, String transferGroup,
                                    Map<String, String> metadata, String idempotencyKey);

    ReversalSnapshot reverseTransfer(String transferId, Money amount, Map<String, String> metadata,
                                     String idempotencyKey);

    /** Payout from a connected account's balance (accounts use the manual payout schedule). */
    PayoutSnapshot createPayout(String connectedAccount, Money amount, Map<String, String> metadata,
                                String idempotencyKey);

    /** Platform balance transactions created in [from, to) (all pages). */
    List<BalanceTxn> balanceTransactions(Instant from, Instant to);

    /** Creates the Stripe Customer of an AfroTransact customer (idempotent per customer). Returns cus_…. */
    String createCustomer(String afroCustomerId, String idempotencyKey);

    /** SetupIntent to save a method for future (off-session) payments; bank accounts verify via Financial
     *  Connections, else microdeposits. */
    SetupIntentSnapshot createSetupIntent(String stripeCustomerId, List<String> paymentMethodTypes,
                                          String idempotencyKey);

    PaymentMethodSnapshot retrievePaymentMethod(String paymentMethodId);

    void detachPaymentMethod(String paymentMethodId, String idempotencyKey);

    /** Sets {@code invoice_settings.default_payment_method} of the customer. */
    void setDefaultPaymentMethod(String stripeCustomerId, String paymentMethodId, String idempotencyKey);

    /**
     * Ephemeral key of a Stripe Customer for the mobile PaymentSheet (S241).
     *
     * @param stripeVersion the Stripe API version the mobile SDK asked for
     */
    EphemeralKeySnapshot createEphemeralKey(String stripeCustomerId, String stripeVersion, String idempotencyKey);

    /** An ephemeral key: the secret is handed to the shopper's app only. */
    record EphemeralKeySnapshot(String id, String secret, Instant expiresAt) {
    }

    /** A SetupIntent. */
    record SetupIntentSnapshot(String id, String clientSecret, String status) {
    }

    /** Display data of a payment method (never the full number). */
    record PaymentMethodSnapshot(String id, String type, String customer, String brand, String last4,
                                 Integer expMonth, Integer expYear, String bankName, String accountType) {
    }

    /**
     * PaymentIntent creation parameters.
     *
     * @param saveForOffSession {@code setup_future_usage=off_session}: the confirmed method is attached to the
     *                          customer for later off-session charges (needs a customer)
     */
    record CreateIntent(Money amount, String customerId, List<String> paymentMethodTypes, String description,
                        Map<String, String> metadata, String transferGroup, boolean saveForOffSession) {

        public CreateIntent {
            paymentMethodTypes = paymentMethodTypes == null ? List.of() : List.copyOf(paymentMethodTypes);
            metadata = Map.copyOf(metadata);
        }
    }

    /** What AfroTransact needs from a PaymentIntent. */
    record IntentSnapshot(String id, String status, String clientSecret, long amount, long amountCapturable,
                          long amountReceived, String latestChargeId, String failureMessage, String nextActionType) {
    }

    /** Off-session PaymentIntent parameters (confirm=true, off_session=true, capture_method=manual). */
    record OffSessionIntent(Money amount, String customerId, String paymentMethodId, String description,
                            Map<String, String> metadata, String transferGroup) {

        public OffSessionIntent {
            metadata = Map.copyOf(metadata);
        }
    }

    /**
     * Result of an off-session confirmation.
     *
     * @param errorCode   Stripe error code when the confirmation failed, e.g. {@code authentication_required}
     * @param declineCode card decline code, if any
     */
    record OffSessionAttempt(IntentSnapshot intent, String errorCode, String declineCode, String errorMessage) {

        public static final String AUTHENTICATION_REQUIRED = "authentication_required";

        public boolean authenticationRequired() {
            return AUTHENTICATION_REQUIRED.equals(errorCode) || AUTHENTICATION_REQUIRED.equals(declineCode)
                    || intent != null && "requires_action".equals(intent.status());
        }
    }

    /** A Stripe account debit (py_…). */
    record AccountDebitSnapshot(String id, long amount, String status) {
    }

    record RefundSnapshot(String id, String status, long amount) {
    }

    record TransferSnapshot(String id, long amount, String destination) {
    }

    record ReversalSnapshot(String id, String transferId, long amount) {
    }

    record PayoutSnapshot(String id, String status, long amount) {
    }

    /**
     * One Stripe balance transaction.
     *
     * @param source charge / refund / transfer / transfer reversal / dispute id
     * @param net    amount − fee, signed (credit to the balance positive)
     */
    record BalanceTxn(String id, String type, String source, long amount, long fee, long net, String currency,
                      Instant created) {
    }
}
