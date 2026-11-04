package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.payments.domain.PaymentMethodType;
import com.afrotransact.payments.domain.SavedPaymentMethod;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.PaymentMethodSnapshot;
import com.afrotransact.payments.port.WalletRepository;
import com.afrotransact.payments.port.WalletRepository.StripeCustomer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps the local copy of saved methods in sync with Stripe — on every wallet listing (read from Stripe) and from the
 * {@code payment_method.*} / {@code setup_intent.*} webhooks — and picks a default automatically.
 */
@Component
class WalletSync {

    private static final Logger LOG = LoggerFactory.getLogger(WalletSync.class);

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
        SavedPaymentMethod method = saved(pm, customer.get().customerId(), type.get(), verified);
        wallet.upsertMethod(method);
        if (customer.get().defaultPaymentMethodId() == null) {
            makeDefault(customer.get(), pm.id());
        }
        return Optional.of(method);
    }

    /**
     * Brings the local copy in line with what Stripe holds for the customer now (read on every wallet listing, so the
     * list never waits for a webhook): every attached method of a known type is recorded, and when the snapshot is
     * authoritative a verified method Stripe no longer has is marked detached. A bank account recorded as pending
     * verification stays pending until {@code setup_intent.succeeded}. The default follows Stripe's
     * {@code invoice_settings.default_payment_method}; without one the current default stays, and a wallet without
     * any gets its oldest verified method (set at Stripe too).
     */
    void reconcile(StripeCustomer customer, StripeGateway.CustomerWalletSnapshot live) {
        String customerId = customer.customerId();
        Set<String> listed = new HashSet<>();
        for (PaymentMethodSnapshot pm : live.methods()) {
            Optional<PaymentMethodType> type = PaymentMethodType.ofStripe(pm.type());
            if (type.isEmpty()) {
                LOG.debug("Saved payment method {} of type {} is not offered; not listed", pm.id(), pm.type());
                continue;
            }
            listed.add(pm.id());
            Optional<SavedPaymentMethod> known = wallet.findMethod(pm.id());
            boolean verified = type.get() != PaymentMethodType.US_BANK_ACCOUNT
                    || known.map(SavedPaymentMethod::verified).orElse(true);
            wallet.upsertMethod(saved(pm, customerId, type.get(), verified));
        }
        List<SavedPaymentMethod> methods = wallet.methods(customerId);
        if (live.authoritative()) {
            Instant now = Instant.now(clock);
            for (SavedPaymentMethod m : methods) {
                if (m.verified() && !listed.contains(m.id())) {
                    wallet.markDetached(m.id(), now);
                }
            }
            methods = wallet.methods(customerId);
        }
        List<SavedPaymentMethod> usable = methods.stream().filter(SavedPaymentMethod::verified).toList();
        String stripeDefault = live.defaultPaymentMethodId();
        String current = customer.defaultPaymentMethodId();
        if (stripeDefault != null && usable.stream().anyMatch(m -> m.id().equals(stripeDefault))) {
            if (!stripeDefault.equals(current)) {
                wallet.setDefault(customerId, stripeDefault);
            }
        } else if (current == null || methods.stream().noneMatch(m -> m.id().equals(current))) {
            if (usable.isEmpty()) {
                if (current != null) {
                    wallet.setDefault(customerId, null);
                }
            } else {
                makeDefault(customer, usable.getFirst().id());
            }
        }
    }

    private SavedPaymentMethod saved(PaymentMethodSnapshot pm, String customerId, PaymentMethodType type,
                                     boolean verified) {
        return new SavedPaymentMethod(pm.id(), customerId, type, pm.brand(), pm.last4(), pm.expMonth(),
                pm.expYear(), pm.bankName(), pm.accountType(), pm.wallet(), verified, Instant.now(clock));
    }

    /** The shopper's Stripe Customer id, if one was created. */
    Optional<String> stripeCustomerOf(String customerId) {
        return wallet.findCustomer(customerId).map(StripeCustomer::stripeCustomerId);
    }

    /** The shopper's Stripe Customer, created (idempotently per shopper) on first use; locked. */
    StripeCustomer ensureCustomer(String customerId) {
        Optional<StripeCustomer> existing = wallet.lockCustomer(customerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        String stripeId = stripe.createCustomer(customerId, "customer:" + customerId);
        wallet.insertCustomer(customerId, stripeId, Instant.now(clock));
        return wallet.lockCustomer(customerId).orElseThrow();
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
