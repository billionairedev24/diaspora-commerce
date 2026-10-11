package com.afrotransact.payments.adapter.out.localtest;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.payments.domain.LocalTestPaymentsPolicy;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.StripeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local profile's stand-in for Stripe ({@link LocalTestPaymentsPolicy}), wired only when the policy allows it. It
 * keeps PaymentIntents in memory and behaves like Stripe in test mode with the card 4242 4242 4242 4242: an intent
 * waits for its payment method until the shopper confirms (the storefront's labelled test card form, or the seeder),
 * and the first status read after that finds it authorised ({@code requires_capture}). Captures, refunds, transfers
 * and payouts succeed at once. Ids start with {@code _localtest_} ({@code pi_localtest_…}) so they are never mistaken
 * for Stripe's. After a restart an unknown intent reads as authorised; payments keeps the amounts itself.
 */
public class LocalTestStripeGateway implements StripeGateway {

    private static final Logger LOG = LoggerFactory.getLogger(LocalTestStripeGateway.class);
    private static final String REQUIRES_PAYMENT_METHOD = "requires_payment_method";
    private static final String REQUIRES_CAPTURE = "requires_capture";
    private static final String SUCCEEDED = "succeeded";
    private static final String CANCELED = "canceled";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Intent> intents = new ConcurrentHashMap<>();
    private final Map<String, String> byIdempotencyKey = new ConcurrentHashMap<>();

    public LocalTestStripeGateway() {
        LOG.warn("[local] Payments use the local test stand-in for Stripe: every payment is authorised like test card "
                + "4242 4242 4242 4242 and no money moves. Put STRIPE_SECRET_KEY in .local/secrets.env for Stripe test "
                + "mode.");
    }

    /** One in-memory PaymentIntent. */
    private record Intent(String id, long amount, String status, long captured) {

        Intent with(String nextStatus, long nextCaptured) {
            return new Intent(id, amount, nextStatus, nextCaptured);
        }

        IntentSnapshot snapshot() {
            long capturable = REQUIRES_CAPTURE.equals(status) ? Math.max(0, amount - captured) : 0;
            return new IntentSnapshot(id, status, id + "_secret_localtest", amount, capturable, captured,
                    captured > 0 ? "ch" + id.substring(2) : null, null, null);
        }
    }

    private String id(String prefix) {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        return prefix + "_localtest_" + HexFormat.of().formatHex(bytes);
    }

    private Intent create(long amount, String status, String idempotencyKey) {
        String key = idempotencyKey == null ? id("key") : idempotencyKey;
        String existing = byIdempotencyKey.computeIfAbsent(key, k -> {
            Intent intent = new Intent(id("pi"), amount, status, 0);
            intents.put(intent.id(), intent);
            return intent.id();
        });
        return intents.get(existing);
    }

    /** An intent this process created, or (after a restart) one that reads as authorised. */
    private Intent find(String intentId) {
        return intents.computeIfAbsent(intentId, id -> new Intent(id, 0, REQUIRES_CAPTURE, 0));
    }

    @Override
    public IntentSnapshot createIntent(CreateIntent request, String idempotencyKey) {
        return create(request.amount().minor(), REQUIRES_PAYMENT_METHOD, idempotencyKey).snapshot();
    }

    /** The shopper confirmed with the test card: the first read after creation finds the intent authorised. */
    @Override
    public IntentSnapshot retrieveIntent(String intentId) {
        return intents.compute(intentId, (id, i) -> {
            if (i == null) {
                return new Intent(id, 0, REQUIRES_CAPTURE, 0);
            }
            return REQUIRES_PAYMENT_METHOD.equals(i.status()) ? i.with(REQUIRES_CAPTURE, 0) : i;
        }).snapshot();
    }

    @Override
    public IntentSnapshot capture(String intentId, Money amount, boolean finalCapture, Map<String, String> metadata,
                                  String idempotencyKey) {
        find(intentId);
        return intents.computeIfPresent(intentId, (id, i) -> i.with(finalCapture ? SUCCEEDED : REQUIRES_CAPTURE,
                i.captured() + amount.minor())).snapshot();
    }

    @Override
    public IntentSnapshot cancel(String intentId, String idempotencyKey) {
        find(intentId);
        return intents.computeIfPresent(intentId, (id, i) -> i.with(CANCELED, i.captured())).snapshot();
    }

    @Override
    public IntentSnapshot releaseUncaptured(String intentId, String idempotencyKey) {
        find(intentId);
        return intents.computeIfPresent(intentId, (id, i) -> i.with(i.captured() > 0 ? SUCCEEDED : CANCELED,
                i.captured())).snapshot();
    }

    @Override
    public OffSessionAttempt createOffSessionIntent(OffSessionIntent request, String idempotencyKey) {
        return new OffSessionAttempt(create(request.amount().minor(), REQUIRES_CAPTURE, idempotencyKey).snapshot(),
                null, null, null);
    }

    @Override
    public AccountDebitSnapshot debitConnectedAccount(String connectedAccount, Money amount, String description,
                                                      Map<String, String> metadata, String idempotencyKey) {
        return new AccountDebitSnapshot(id("py"), amount.minor(), SUCCEEDED);
    }

    @Override
    public RefundSnapshot refund(String intentId, Money amount, Map<String, String> metadata, String idempotencyKey) {
        return new RefundSnapshot(id("re"), SUCCEEDED, amount.minor());
    }

    /** No balance transactions: payments books the fee schedule's estimate instead. */
    @Override
    public List<BalanceTxn> chargeBalanceTransactions(String chargeId) {
        return List.of();
    }

    @Override
    public TransferSnapshot createTransfer(Money amount, String destinationAccount, String transferGroup,
                                           Map<String, String> metadata, String idempotencyKey) {
        return new TransferSnapshot(id("tr"), amount.minor(), destinationAccount);
    }

    @Override
    public ReversalSnapshot reverseTransfer(String transferId, Money amount, Map<String, String> metadata,
                                            String idempotencyKey) {
        return new ReversalSnapshot(id("trr"), transferId, amount.minor());
    }

    @Override
    public PayoutSnapshot createPayout(String connectedAccount, Money amount, Map<String, String> metadata,
                                       String idempotencyKey) {
        return new PayoutSnapshot(id("po"), "pending", amount.minor());
    }

    @Override
    public List<BalanceTxn> balanceTransactions(Instant from, Instant to) {
        return List.of();
    }

    @Override
    public String createCustomer(String afroCustomerId, String idempotencyKey) {
        return id("cus");
    }

    @Override
    public SetupIntentSnapshot createSetupIntent(String stripeCustomerId, List<String> paymentMethodTypes,
                                                 String idempotencyKey) {
        String id = id("seti");
        return new SetupIntentSnapshot(id, id + "_secret_localtest", REQUIRES_PAYMENT_METHOD);
    }

    /** Every saved method reads as the test Visa ending 4242. */
    @Override
    public PaymentMethodSnapshot retrievePaymentMethod(String paymentMethodId) {
        return new PaymentMethodSnapshot(paymentMethodId, "card", null, "visa", "4242", 12, 2034, null, null);
    }

    @Override
    public void detachPaymentMethod(String paymentMethodId, String idempotencyKey) {
        // nothing is stored outside payments' own wallet
    }

    @Override
    public void setDefaultPaymentMethod(String stripeCustomerId, String paymentMethodId, String idempotencyKey) {
        // nothing is stored outside payments' own wallet
    }

    @Override
    public EphemeralKeySnapshot createEphemeralKey(String stripeCustomerId, String stripeVersion,
                                                   String idempotencyKey) {
        return new EphemeralKeySnapshot(id("ephkey"), id("ek_test"), Instant.now().plusSeconds(3600));
    }
}
