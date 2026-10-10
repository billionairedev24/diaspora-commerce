package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.payments.domain.PaymentMethodType;
import com.afrotransact.payments.domain.SavedPaymentMethod;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.PaymentMethodSnapshot;
import com.afrotransact.payments.port.WalletRepository;
import com.afrotransact.payments.port.WalletRepository.StripeCustomer;
import org.springframework.stereotype.Component;

/** Keeps the local list of saved methods in sync with Stripe (webhooks) and picks a default automatically. */
@Component
class WalletSync {

    private final WalletRepository wallet;
    private final StripeGateway stripe;
    private final Clock clock;

    WalletSync(WalletRepository wallet, StripeGateway stripe, Clock clock) {
        this.wallet = wallet;
        this.stripe = stripe;
        this.clock = clock;
    }

    /**
     * Records a method attached to one of our Stripe Customers; the first method becomes the default.
     *
     * @param verified false for a bank account still waiting for microdeposit verification
     */
    Optional<SavedPaymentMethod> upsert(PaymentMethodSnapshot pm, boolean verified) {
        Optional<StripeCustomer> customer = pm.customer() == null ? Optional.empty()
                : wallet.findByStripeCustomer(pm.customer());
        Optional<PaymentMethodType> type = PaymentMethodType.ofStripe(pm.type());
        if (customer.isEmpty() || type.isEmpty()) {
            return Optional.empty();
        }
        SavedPaymentMethod method = new SavedPaymentMethod(pm.id(), customer.get().customerId(), type.get(),
                pm.brand(), pm.last4(), pm.expMonth(), pm.expYear(), pm.bankName(), pm.accountType(), verified,
                Instant.now(clock));
        wallet.upsertMethod(method);
        if (customer.get().defaultPaymentMethodId() == null) {
            makeDefault(customer.get(), pm.id());
        }
        return Optional.of(method);
    }

    /** A detached default is replaced by the oldest remaining method (or none). */
    void detached(String paymentMethodId) {
        Optional<SavedPaymentMethod> method = wallet.findMethod(paymentMethodId);
        if (method.isEmpty()) {
            return;
        }
        wallet.markDetached(paymentMethodId, Instant.now(clock));
        StripeCustomer customer = wallet.lockCustomer(method.get().customerId()).orElseThrow();
        if (paymentMethodId.equals(customer.defaultPaymentMethodId())) {
            List<SavedPaymentMethod> remaining = wallet.methods(customer.customerId());
            if (remaining.isEmpty()) {
                wallet.setDefault(customer.customerId(), null);
            } else {
                makeDefault(customer, remaining.getFirst().id());
            }
        }
    }

    void makeDefault(StripeCustomer customer, String paymentMethodId) {
        stripe.setDefaultPaymentMethod(customer.stripeCustomerId(), paymentMethodId,
                "default:" + customer.stripeCustomerId() + ":" + paymentMethodId);
        wallet.setDefault(customer.customerId(), paymentMethodId);
    }
}
