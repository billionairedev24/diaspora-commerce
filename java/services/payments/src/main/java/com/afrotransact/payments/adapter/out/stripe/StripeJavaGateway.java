package com.afrotransact.payments.adapter.out.stripe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGatewayException;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.Dispute;
import com.stripe.model.EphemeralKey;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Payout;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.model.StripeError;
import com.stripe.model.Transfer;
import com.stripe.model.TransferReversal;
import com.stripe.net.RequestOptions;
import com.stripe.param.BalanceTransactionListParams;
import com.stripe.param.ChargeCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerPaymentMethodListParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.EphemeralKeyCreateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodDetachParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferReversalCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * stripe-java ({@link StripeClient}) implementation of {@link StripeGateway}. The client's API base is configurable
 * (tests point it at WireMock; every real environment uses api.stripe.com with a test or live key). Wired by
 * PaymentsConfiguration unless the local profile runs without a Stripe key (LocalTestPaymentsPolicy).
 *
 * <p>Multicapture (several partial captures of one authorisation, "captured per shipment") is a Stripe flexible
 * payments feature that must be enabled for the account; requesting it on an account that is not eligible fails every
 * checkout with "This account is not eligible for the requested card features", even with {@code if_available}. It is
 * therefore only requested when configured ({@code at.payments.stripe.multicapture=true}), and when Stripe still refuses
 * it the intent is created once more without it (and it is not requested again by this process). Intents without
 * multicapture use the idempotency key {@code <key>:single}, so a key is never reused with different parameters.
 */
public class StripeJavaGateway implements StripeGateway {

    private static final Logger LOG = LoggerFactory.getLogger(StripeJavaGateway.class);
    /** Payment methods per page when listing a customer's wallet (Stripe's maximum). */
    private static final long WALLET_PAGE = 100L;

    /** Suffix of the idempotency key of a single-capture intent. */
    static final String SINGLE_CAPTURE_KEY = ":single";

    private final StripeClient client;
    private final AtomicBoolean multicapture;

    /** A gateway that creates single-capture intents (accounts without flexible payments). */
    public StripeJavaGateway(StripeClient client) {
        this(client, false);
    }

    /**
     * @param requestMulticapture request {@code request_multicapture=if_available} (only for accounts Stripe enabled
     *                            for multicapture)
     */
    public StripeJavaGateway(StripeClient client, boolean requestMulticapture) {
        this.client = client;
        this.multicapture = new AtomicBoolean(requestMulticapture);
    }

    @Override
    public IntentSnapshot createIntent(CreateIntent request, String idempotencyKey) {
        if (multicapture.get()) {
            try {
                return intent(client.v1().paymentIntents().create(intentParams(request, true).build(),
                        key(idempotencyKey)), true);
            } catch (InvalidRequestException e) {
                if (!multicaptureRefused(e)) {
                    throw gatewayError(e);
                }
                multicapture.set(false);
                LOG.warn("Stripe refused multicapture for this account ({}); intents are created for a single capture "
                        + "from now on. Set at.payments.stripe.multicapture=false (STRIPE_MULTICAPTURE) until Stripe "
                        + "enables flexible payments.", e.getMessage());
            } catch (StripeException e) {
                throw gatewayError(e);
            }
        }
        PaymentIntentCreateParams params = intentParams(request, false).build();
        return call(() -> intent(client.v1().paymentIntents().create(params,
                key(idempotencyKey + SINGLE_CAPTURE_KEY)), false));
    }

    /**
     * True when Stripe refused the multicapture request itself: the account is not eligible for flexible payments
     * ("This account is not eligible for the requested card features") or does not know the parameter.
     */
    static boolean multicaptureRefused(StripeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        String param = e.getStripeError() == null || e.getStripeError().getParam() == null ? ""
                : e.getStripeError().getParam();
        return message.contains("not eligible for the requested card features")
                || message.contains("flexible-payments") || message.contains("flexible payments")
                || param.contains("request_multicapture");
    }

    private static PaymentIntentCreateParams.Builder intentParams(CreateIntent request, boolean multicapture) {
        PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                .setAmount(request.amount().minor())
                .setCurrency(lower(request.amount().currency()))
                // Capture per shipment where the method can hold funds (manual capture per method below); methods that
                // cannot (US bank account / ACH) are charged when confirmed and settle in a few days. A top-level
                // manual capture would hide them from the Payment Element and refuse saved bank accounts.
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC_ASYNC)
                .setDescription(request.description())
                .setTransferGroup(request.transferGroup())
                .putAllMetadata(request.metadata());
        PaymentIntentCreateParams.PaymentMethodOptions.Card.Builder card = PaymentIntentCreateParams.PaymentMethodOptions
                .Card.builder().setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Card.CaptureMethod.MANUAL);
        if (multicapture) {
            card.setRequestMulticapture(PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestMulticapture
                    .IF_AVAILABLE);
        }
        params.setPaymentMethodOptions(PaymentIntentCreateParams.PaymentMethodOptions.builder()
                .setCard(card.build())
                .setKlarna(PaymentIntentCreateParams.PaymentMethodOptions.Klarna.builder()
                        .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Klarna.CaptureMethod.MANUAL)
                        .build())
                .setAffirm(PaymentIntentCreateParams.PaymentMethodOptions.Affirm.builder()
                        .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Affirm.CaptureMethod.MANUAL)
                        .build())
                .setAfterpayClearpay(PaymentIntentCreateParams.PaymentMethodOptions.AfterpayClearpay.builder()
                        .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.AfterpayClearpay.CaptureMethod
                                .MANUAL)
                        .build())
                .setAmazonPay(PaymentIntentCreateParams.PaymentMethodOptions.AmazonPay.builder()
                        .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.AmazonPay.CaptureMethod.MANUAL)
                        .build())
                .setCashapp(PaymentIntentCreateParams.PaymentMethodOptions.Cashapp.builder()
                        .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Cashapp.CaptureMethod.MANUAL)
                        .build())
                .setLink(PaymentIntentCreateParams.PaymentMethodOptions.Link.builder()
                        .setCaptureMethod(PaymentIntentCreateParams.PaymentMethodOptions.Link.CaptureMethod.MANUAL)
                        .build())
                .build());
        if (request.customerId() != null && !request.customerId().isBlank()) {
            params.setCustomer(request.customerId());
            if (request.saveForOffSession()) {
                params.setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION);
            }
        }
        if (request.paymentMethodTypes().isEmpty()) {
            params.setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                    .setEnabled(true).build());
        } else {
            for (String type : request.paymentMethodTypes()) {
                params.addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType
                        .valueOf(type.trim().toUpperCase(Locale.ROOT)));
            }
        }
        return params;
    }

    @Override
    public IntentSnapshot retrieveIntent(String intentId) {
        return call(() -> intent(client.v1().paymentIntents().retrieve(intentId)));
    }

    @Override
    public IntentSnapshot capture(String intentId, Money amount, Boolean finalCapture, Map<String, String> metadata,
                                  String idempotencyKey) {
        PaymentIntentCaptureParams.Builder params = PaymentIntentCaptureParams.builder()
                .setAmountToCapture(amount.minor())
                .putAllMetadata(metadata);
        if (finalCapture != null) {
            params.setFinalCapture(finalCapture);
        }
        return call(() -> intent(client.v1().paymentIntents().capture(intentId, params.build(), key(idempotencyKey))));
    }

    @Override
    public IntentSnapshot cancel(String intentId, String idempotencyKey) {
        return call(() -> intent(client.v1().paymentIntents().cancel(intentId,
                PaymentIntentCancelParams.builder().build(), key(idempotencyKey))));
    }

    @Override
    public IntentSnapshot releaseUncaptured(String intentId, String idempotencyKey) {
        return call(() -> intent(client.v1().paymentIntents().cancel(intentId,
                PaymentIntentCancelParams.builder().build(), key(idempotencyKey))));
    }

    @Override
    public OffSessionAttempt createOffSessionIntent(OffSessionIntent request, String idempotencyKey) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(request.amount().minor())
                .setCurrency(lower(request.amount().currency()))
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                .setCustomer(request.customerId())
                .setPaymentMethod(request.paymentMethodId())
                .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD)
                .setOffSession(true)
                .setConfirm(true)
                .setDescription(request.description())
                .setTransferGroup(request.transferGroup())
                .putAllMetadata(request.metadata())
                .build();
        try {
            return new OffSessionAttempt(intent(client.v1().paymentIntents().create(params, key(idempotencyKey))),
                    null, null, null);
        } catch (CardException e) {
            StripeError error = e.getStripeError();
            PaymentIntent pi = error == null ? null : error.getPaymentIntent();
            if (pi == null) {
                throw gatewayError(e);
            }
            return new OffSessionAttempt(intent(pi), e.getCode(), error.getDeclineCode(), error.getMessage());
        } catch (StripeException e) {
            throw gatewayError(e);
        }
    }

    @Override
    public AccountDebitSnapshot debitConnectedAccount(String connectedAccount, Money amount, String description,
                                                      Map<String, String> metadata, String idempotencyKey) {
        ChargeCreateParams params = ChargeCreateParams.builder().setAmount(amount.minor())
                .setCurrency(lower(amount.currency())).setSource(connectedAccount).setDescription(description)
                .putAllMetadata(metadata).build();
        return call(() -> {
            Charge c = client.v1().charges().create(params, key(idempotencyKey));
            return new AccountDebitSnapshot(c.getId(), nz(c.getAmount()), c.getStatus());
        });
    }

    @Override
    public RefundSnapshot refund(String intentId, Money amount, Map<String, String> metadata, String idempotencyKey) {
        RefundCreateParams params = RefundCreateParams.builder().setPaymentIntent(intentId)
                .setAmount(amount.minor()).putAllMetadata(metadata).build();
        return call(() -> {
            Refund r = client.v1().refunds().create(params, key(idempotencyKey));
            return new RefundSnapshot(r.getId(), r.getStatus(), nz(r.getAmount()));
        });
    }

    @Override
    public List<BalanceTxn> chargeBalanceTransactions(String chargeId) {
        BalanceTransactionListParams params = BalanceTransactionListParams.builder().setSource(chargeId)
                .setLimit(100L).build();
        return call(() -> list(client.v1().balanceTransactions().list(params).autoPagingIterable()));
    }

    @Override
    public TransferSnapshot createTransfer(Money amount, String destinationAccount, String transferGroup,
                                           Map<String, String> metadata, String idempotencyKey) {
        TransferCreateParams params = TransferCreateParams.builder().setAmount(amount.minor())
                .setCurrency(lower(amount.currency())).setDestination(destinationAccount)
                .setTransferGroup(transferGroup).putAllMetadata(metadata).build();
        return call(() -> {
            Transfer t = client.v1().transfers().create(params, key(idempotencyKey));
            return new TransferSnapshot(t.getId(), nz(t.getAmount()), t.getDestination());
        });
    }

    @Override
    public ReversalSnapshot reverseTransfer(String transferId, Money amount, Map<String, String> metadata,
                                            String idempotencyKey) {
        TransferReversalCreateParams params = TransferReversalCreateParams.builder().setAmount(amount.minor())
                .putAllMetadata(metadata).build();
        return call(() -> {
            TransferReversal r = client.v1().transfers().reversals().create(transferId, params, key(idempotencyKey));
            return new ReversalSnapshot(r.getId(), transferId, nz(r.getAmount()));
        });
    }

    @Override
    public PayoutSnapshot createPayout(String connectedAccount, Money amount, Map<String, String> metadata,
                                       String idempotencyKey) {
        PayoutCreateParams params = PayoutCreateParams.builder().setAmount(amount.minor())
                .setCurrency(lower(amount.currency())).putAllMetadata(metadata).build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(idempotencyKey)
                .setStripeAccount(connectedAccount).build();
        return call(() -> {
            Payout p = client.v1().payouts().create(params, options);
            return new PayoutSnapshot(p.getId(), p.getStatus(), nz(p.getAmount()));
        });
    }

    @Override
    public List<BalanceTxn> balanceTransactions(Instant from, Instant to) {
        BalanceTransactionListParams params = BalanceTransactionListParams.builder()
                .setCreated(BalanceTransactionListParams.Created.builder().setGte(from.getEpochSecond())
                        .setLt(to.getEpochSecond()).build())
                .setLimit(100L).build();
        return call(() -> list(client.v1().balanceTransactions().list(params).autoPagingIterable()));
    }

    @Override
    public Optional<ObjectSnapshot> lookup(String ref, String connectedAccount) {
        String prefix = ref.contains("_") ? ref.substring(0, ref.indexOf('_')) : ref;
        RequestOptions onAccount = connectedAccount == null ? RequestOptions.builder().build()
                : RequestOptions.builder().setStripeAccount(connectedAccount).build();
        try {
            return Optional.ofNullable(switch (prefix) {
                case "po" -> payoutObject(client.v1().payouts().retrieve(ref, onAccount));
                case "tr" -> transferObject(client.v1().transfers().retrieve(ref));
                case "pi" -> intentObject(client.v1().paymentIntents().retrieve(ref));
                case "ch", "py" -> chargeObject(client.v1().charges().retrieve(ref));
                case "re", "pyr" -> refundObject(client.v1().refunds().retrieve(ref));
                case "txn" -> balanceTransactionObject(client.v1().balanceTransactions().retrieve(ref));
                case "dp" -> disputeObject(client.v1().disputes().retrieve(ref));
                case "acct" -> accountObject(client.v1().accounts().retrieve(ref));
                default -> null;
            });
        } catch (StripeException e) {
            if (e.getStatusCode() != null && e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw gatewayError(e);
        }
    }

    @Override
    public ConnectedBalanceSnapshot connectedBalance(String connectedAccount) {
        RequestOptions onAccount = RequestOptions.builder().setStripeAccount(connectedAccount).build();
        return call(() -> {
            Balance b = client.v1().balance().retrieve(onAccount);
            String currency = b.getAvailable() == null || b.getAvailable().isEmpty() ? "usd"
                    : b.getAvailable().get(0).getCurrency();
            long available = b.getAvailable() == null ? 0 : b.getAvailable().stream()
                    .filter(a -> currency.equalsIgnoreCase(a.getCurrency())).mapToLong(a -> nz(a.getAmount())).sum();
            long pending = b.getPending() == null ? 0 : b.getPending().stream()
                    .filter(a -> currency.equalsIgnoreCase(a.getCurrency())).mapToLong(a -> nz(a.getAmount())).sum();
            return new ConnectedBalanceSnapshot(connectedAccount, currency.toUpperCase(Locale.ROOT), available, pending);
        });
    }

    private static Instant at(Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }

    private static void link(Map<String, String> links, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            links.put(key, value.toString());
        }
    }

    private static ObjectSnapshot payoutObject(Payout p) {
        Map<String, String> links = new LinkedHashMap<>();
        link(links, "arrivalDate", at(p.getArrivalDate()));
        link(links, "method", p.getMethod());
        link(links, "balanceTransaction", p.getBalanceTransaction());
        link(links, "failureCode", p.getFailureCode());
        return new ObjectSnapshot(p.getId(), "Payout", p.getStatus(), nz(p.getAmount()), p.getCurrency(),
                at(p.getCreated()), p.getDescription(), p.getFailureMessage(), links);
    }

    private static ObjectSnapshot transferObject(Transfer t) {
        Map<String, String> links = new LinkedHashMap<>();
        link(links, "destination", t.getDestination());
        link(links, "transferGroup", t.getTransferGroup());
        link(links, "reversed", t.getReversed());
        link(links, "amountReversedMinor", t.getAmountReversed());
        link(links, "balanceTransaction", t.getBalanceTransaction());
        return new ObjectSnapshot(t.getId(), "Transfer", Boolean.TRUE.equals(t.getReversed()) ? "reversed" : "paid",
                nz(t.getAmount()), t.getCurrency(), at(t.getCreated()), t.getDescription(), null, links);
    }

    private static ObjectSnapshot intentObject(PaymentIntent pi) {
        Map<String, String> links = new LinkedHashMap<>();
        link(links, "charge", pi.getLatestCharge());
        link(links, "amountReceivedMinor", pi.getAmountReceived());
        link(links, "transferGroup", pi.getTransferGroup());
        return new ObjectSnapshot(pi.getId(), "Payment intent", pi.getStatus(), nz(pi.getAmount()), pi.getCurrency(),
                at(pi.getCreated()), pi.getDescription(), null, links);
    }

    private static ObjectSnapshot chargeObject(Charge c) {
        Map<String, String> links = new LinkedHashMap<>();
        link(links, "paymentIntent", c.getPaymentIntent());
        link(links, "amountRefundedMinor", c.getAmountRefunded());
        link(links, "disputed", c.getDisputed());
        link(links, "balanceTransaction", c.getBalanceTransaction());
        return new ObjectSnapshot(c.getId(), "Charge", c.getStatus(), nz(c.getAmount()), c.getCurrency(),
                at(c.getCreated()), c.getDescription(), c.getFailureMessage(), links);
    }

    private static ObjectSnapshot refundObject(Refund r) {
        Map<String, String> links = new LinkedHashMap<>();
        link(links, "charge", r.getCharge());
        link(links, "paymentIntent", r.getPaymentIntent());
        link(links, "balanceTransaction", r.getBalanceTransaction());
        return new ObjectSnapshot(r.getId(), "Refund", r.getStatus(), nz(r.getAmount()), r.getCurrency(),
                at(r.getCreated()), r.getReason(), r.getFailureReason(), links);
    }

    private static ObjectSnapshot balanceTransactionObject(BalanceTransaction t) {
        Map<String, String> links = new LinkedHashMap<>();
        link(links, "type", t.getType());
        link(links, "source", t.getSource());
        link(links, "feeMinor", t.getFee());
        link(links, "netMinor", t.getNet());
        link(links, "availableOn", at(t.getAvailableOn()));
        return new ObjectSnapshot(t.getId(), "Balance transaction", t.getStatus(), nz(t.getAmount()), t.getCurrency(),
                at(t.getCreated()), t.getDescription(), null, links);
    }

    private static ObjectSnapshot disputeObject(Dispute d) {
        Map<String, String> links = new LinkedHashMap<>();
        link(links, "charge", d.getCharge());
        link(links, "reason", d.getReason());
        return new ObjectSnapshot(d.getId(), "Dispute", d.getStatus(), nz(d.getAmount()), d.getCurrency(),
                at(d.getCreated()), null, null, links);
    }

    private static ObjectSnapshot accountObject(Account a) {
        Map<String, String> links = new LinkedHashMap<>();
        link(links, "chargesEnabled", a.getChargesEnabled());
        link(links, "payoutsEnabled", a.getPayoutsEnabled());
        link(links, "detailsSubmitted", a.getDetailsSubmitted());
        link(links, "country", a.getCountry());
        return new ObjectSnapshot(a.getId(), "Connected account", Boolean.TRUE.equals(a.getPayoutsEnabled())
                ? "payouts enabled" : "payouts not enabled", 0, null, at(a.getCreated()), a.getEmail(), null, links);
    }

    @Override
    public String createCustomer(String afroCustomerId, String idempotencyKey) {
        CustomerCreateParams params = CustomerCreateParams.builder()
                .putMetadata("afrotransact_customer_id", afroCustomerId).build();
        return call(() -> {
            Customer c = client.v1().customers().create(params, key(idempotencyKey));
            return c.getId();
        });
    }

    @Override
    public EphemeralKeySnapshot createEphemeralKey(String stripeCustomerId, String stripeVersion,
                                                   String idempotencyKey) {
        EphemeralKeyCreateParams params = EphemeralKeyCreateParams.builder().setCustomer(stripeCustomerId)
                .setStripeVersion(stripeVersion).build();
        return call(() -> {
            EphemeralKey k = client.v1().ephemeralKeys().create(params, key(idempotencyKey));
            return new EphemeralKeySnapshot(k.getId(), k.getSecret(),
                    k.getExpires() == null ? null : Instant.ofEpochSecond(k.getExpires()));
        });
    }

    @Override
    public SetupIntentSnapshot createSetupIntent(String stripeCustomerId, List<String> paymentMethodTypes,
                                                 String idempotencyKey) {
        SetupIntentCreateParams.Builder params = SetupIntentCreateParams.builder().setCustomer(stripeCustomerId)
                .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION);
        for (String type : paymentMethodTypes) {
            params.addAllowedPaymentMethodType(SetupIntentCreateParams.AllowedPaymentMethodType
                    .valueOf(type.toUpperCase(Locale.ROOT)));
        }
        if (paymentMethodTypes.contains("us_bank_account")) {
            params.setPaymentMethodOptions(SetupIntentCreateParams.PaymentMethodOptions.builder()
                    .setUsBankAccount(SetupIntentCreateParams.PaymentMethodOptions.UsBankAccount.builder()
                            .setVerificationMethod(SetupIntentCreateParams.PaymentMethodOptions.UsBankAccount
                                    .VerificationMethod.AUTOMATIC)
                            .build())
                    .build());
        }
        return call(() -> {
            SetupIntent si = client.v1().setupIntents().create(params.build(), key(idempotencyKey));
            return new SetupIntentSnapshot(si.getId(), si.getClientSecret(), si.getStatus());
        });
    }

    @Override
    public PaymentMethodSnapshot retrievePaymentMethod(String paymentMethodId) {
        return call(() -> paymentMethod(client.v1().paymentMethods().retrieve(paymentMethodId)));
    }

    @Override
    public CustomerWalletSnapshot customerWallet(String stripeCustomerId) {
        return call(() -> {
            Customer customer = client.v1().customers().retrieve(stripeCustomerId);
            String defaultMethod = customer.getInvoiceSettings() == null ? null
                    : customer.getInvoiceSettings().getDefaultPaymentMethod();
            List<PaymentMethodSnapshot> methods = new ArrayList<>();
            CustomerPaymentMethodListParams params = CustomerPaymentMethodListParams.builder()
                    .setLimit(WALLET_PAGE).build();
            for (PaymentMethod pm : client.v1().customers().paymentMethods().list(stripeCustomerId, params)
                    .autoPagingIterable()) {
                methods.add(paymentMethod(pm));
            }
            return new CustomerWalletSnapshot(defaultMethod, methods, true);
        });
    }

    private static PaymentMethodSnapshot paymentMethod(PaymentMethod pm) {
        PaymentMethod.Card card = pm.getCard();
        PaymentMethod.UsBankAccount bank = pm.getUsBankAccount();
        return new PaymentMethodSnapshot(pm.getId(), pm.getType(), pm.getCustomer(),
                card == null ? null : card.getBrand(),
                card != null ? card.getLast4() : bank == null ? null : bank.getLast4(),
                card == null || card.getExpMonth() == null ? null : card.getExpMonth().intValue(),
                card == null || card.getExpYear() == null ? null : card.getExpYear().intValue(),
                bank == null ? null : bank.getBankName(), bank == null ? null : bank.getAccountType(),
                card == null || card.getWallet() == null ? null : card.getWallet().getType());
    }

    @Override
    public void detachPaymentMethod(String paymentMethodId, String idempotencyKey) {
        call(() -> client.v1().paymentMethods().detach(paymentMethodId, PaymentMethodDetachParams.builder().build(),
                key(idempotencyKey)));
    }

    @Override
    public void setDefaultPaymentMethod(String stripeCustomerId, String paymentMethodId, String idempotencyKey) {
        CustomerUpdateParams params = CustomerUpdateParams.builder()
                .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                        .setDefaultPaymentMethod(paymentMethodId).build())
                .build();
        call(() -> client.v1().customers().update(stripeCustomerId, params, key(idempotencyKey)));
    }

    private static List<BalanceTxn> list(Iterable<BalanceTransaction> pages) {
        List<BalanceTxn> out = new ArrayList<>();
        for (BalanceTransaction t : pages) {
            out.add(new BalanceTxn(t.getId(), t.getType(), t.getSource(), nz(t.getAmount()), nz(t.getFee()),
                    nz(t.getNet()), t.getCurrency(), t.getCreated() == null ? null
                    : Instant.ofEpochSecond(t.getCreated())));
        }
        return out;
    }

    private static IntentSnapshot intent(PaymentIntent pi) {
        PaymentIntent.PaymentMethodOptions options = pi.getPaymentMethodOptions();
        boolean multi = options != null && options.getCard() != null
                && "if_available".equals(options.getCard().getRequestMulticapture());
        return intent(pi, multi);
    }

    private static IntentSnapshot intent(PaymentIntent pi, boolean multicapture) {
        StripeError error = pi.getLastPaymentError();
        String failure = error == null ? null : error.getMessage() == null ? error.getDeclineCode()
                : error.getMessage();
        return new IntentSnapshot(pi.getId(), pi.getStatus(), pi.getClientSecret(), nz(pi.getAmount()),
                nz(pi.getAmountCapturable()), nz(pi.getAmountReceived()), pi.getLatestCharge(), failure,
                pi.getNextAction() == null ? null : pi.getNextAction().getType(), multicapture);
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static String lower(String currency) {
        return currency.toLowerCase(Locale.ROOT);
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static <T> T call(StripeCall<T> body) {
        try {
            return body.run();
        } catch (StripeException e) {
            throw gatewayError(e);
        }
    }

    private static StripeGatewayException gatewayError(StripeException e) {
        StripeError error = e.getStripeError();
        return new StripeGatewayException("Stripe: " + e.getMessage(), e.getCode(),
                error == null ? null : error.getDeclineCode(), e.getStatusCode() == null ? 0 : e.getStatusCode(), e);
    }

    /** A Stripe call that may throw a checked {@link StripeException}. */
    @FunctionalInterface
    private interface StripeCall<T> {
        T run() throws StripeException;
    }
}
