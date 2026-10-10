package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.Money;

/**
 * Outbound port to Stripe (PaymentIntents with manual capture, Connect transfers and payouts, balance
 * transactions). Every mutating call takes an idempotency key that callers derive from business ids
 * ({@code <orderId>:capture:<shipmentId>} …) so retries never move money twice.
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

    /** A SetupIntent. */
    record SetupIntentSnapshot(String id, String clientSecret, String status) {
    }

    /** Display data of a payment method (never the full number). */
    record PaymentMethodSnapshot(String id, String type, String customer, String brand, String last4,
                                 Integer expMonth, Integer expYear, String bankName, String accountType) {
    }

    /** PaymentIntent creation parameters. */
    record CreateIntent(Money amount, String customerId, List<String> paymentMethodTypes, String description,
                        Map<String, String> metadata, String transferGroup) {

        public CreateIntent {
            paymentMethodTypes = paymentMethodTypes == null ? List.of() : List.copyOf(paymentMethodTypes);
            metadata = Map.copyOf(metadata);
        }
    }

    /** What AfroTransact needs from a PaymentIntent. */
    record IntentSnapshot(String id, String status, String clientSecret, long amount, long amountCapturable,
                          long amountReceived, String latestChargeId, String failureMessage, String nextActionType) {
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
