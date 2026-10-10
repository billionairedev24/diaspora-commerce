package com.afrotransact.payments.adapter.out.stripe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGatewayException;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
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
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentMethodDetachParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferReversalCreateParams;
import org.springframework.stereotype.Component;

/**
 * stripe-java ({@link StripeClient}) implementation of {@link StripeGateway}. The client's API base is configurable
 * (tests point it at WireMock; every real environment uses api.stripe.com with a test or live key).
 */
@Component
class StripeJavaGateway implements StripeGateway {

    private final StripeClient client;

    StripeJavaGateway(StripeClient client) {
        this.client = client;
    }

    @Override
    public IntentSnapshot createIntent(CreateIntent request, String idempotencyKey) {
        PaymentIntentCreateParams.Builder params = PaymentIntentCreateParams.builder()
                .setAmount(request.amount().minor())
                .setCurrency(lower(request.amount().currency()))
                .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
                .setDescription(request.description())
                .setTransferGroup(request.transferGroup())
                .putAllMetadata(request.metadata())
                .setPaymentMethodOptions(PaymentIntentCreateParams.PaymentMethodOptions.builder()
                        .setCard(PaymentIntentCreateParams.PaymentMethodOptions.Card.builder()
                                .setRequestMulticapture(PaymentIntentCreateParams.PaymentMethodOptions.Card
                                        .RequestMulticapture.IF_AVAILABLE)
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
        return call(() -> intent(client.v1().paymentIntents().create(params.build(), key(idempotencyKey))));
    }

    @Override
    public IntentSnapshot retrieveIntent(String intentId) {
        return call(() -> intent(client.v1().paymentIntents().retrieve(intentId)));
    }

    @Override
    public IntentSnapshot capture(String intentId, Money amount, boolean finalCapture, Map<String, String> metadata,
                                  String idempotencyKey) {
        PaymentIntentCaptureParams params = PaymentIntentCaptureParams.builder()
                .setAmountToCapture(amount.minor())
                .setFinalCapture(finalCapture)
                .putAllMetadata(metadata)
                .build();
        return call(() -> intent(client.v1().paymentIntents().capture(intentId, params, key(idempotencyKey))));
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
    public String createCustomer(String afroCustomerId, String idempotencyKey) {
        CustomerCreateParams params = CustomerCreateParams.builder()
                .putMetadata("afrotransact_customer_id", afroCustomerId).build();
        return call(() -> {
            Customer c = client.v1().customers().create(params, key(idempotencyKey));
            return c.getId();
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
        return call(() -> {
            PaymentMethod pm = client.v1().paymentMethods().retrieve(paymentMethodId);
            PaymentMethod.Card card = pm.getCard();
            PaymentMethod.UsBankAccount bank = pm.getUsBankAccount();
            return new PaymentMethodSnapshot(pm.getId(), pm.getType(), pm.getCustomer(),
                    card == null ? null : card.getBrand(),
                    card != null ? card.getLast4() : bank == null ? null : bank.getLast4(),
                    card == null || card.getExpMonth() == null ? null : card.getExpMonth().intValue(),
                    card == null || card.getExpYear() == null ? null : card.getExpYear().intValue(),
                    bank == null ? null : bank.getBankName(), bank == null ? null : bank.getAccountType());
        });
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
        StripeError error = pi.getLastPaymentError();
        String failure = error == null ? null : error.getMessage() == null ? error.getDeclineCode()
                : error.getMessage();
        return new IntentSnapshot(pi.getId(), pi.getStatus(), pi.getClientSecret(), nz(pi.getAmount()),
                nz(pi.getAmountCapturable()), nz(pi.getAmountReceived()), pi.getLatestCharge(), failure,
                pi.getNextAction() == null ? null : pi.getNextAction().getType());
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
