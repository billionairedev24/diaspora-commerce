package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.PaymentMethodType;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.domain.SavedPaymentMethod;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.WalletRepository;
import com.afrotransact.payments.port.WalletRepository.StripeCustomer;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class PaymentMethodServiceImpl implements PaymentMethodService {

    private final WalletRepository wallet;
    private final WalletSync sync;
    private final StripeGateway stripe;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final Clock clock;

    PaymentMethodServiceImpl(WalletRepository wallet, WalletSync sync, StripeGateway stripe, MoneyTransactions tx,
                             PaymentsAccess access, Clock clock) {
        this.wallet = wallet;
        this.sync = sync;
        this.stripe = stripe;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public Wallet list() {
        String customerId = access.customer().subject();
        Optional<StripeCustomer> customer = wallet.findCustomer(customerId);
        if (customer.isEmpty()) {
            return new Wallet(null, List.of());
        }
        String defaultId = customer.get().defaultPaymentMethodId();
        List<WalletMethod> items = wallet.methods(customerId).stream()
                .map(m -> new WalletMethod(m, m.id().equals(defaultId))).toList();
        return new Wallet(customer.get().stripeCustomerId(), items);
    }

    @Override
    public SetupIntentResult createSetupIntent(List<String> types, String idempotencyKey) {
        String customerId = access.customer().subject();
        List<String> wanted = normaliseTypes(types);
        return tx.serializable(() -> {
            StripeCustomer customer = ensureCustomer(customerId);
            String key = "setup:" + customerId + ":" + (idempotencyKey == null || idempotencyKey.isBlank()
                    ? UUID.randomUUID() : idempotencyKey);
            StripeGateway.SetupIntentSnapshot si = stripe.createSetupIntent(customer.stripeCustomerId(), wanted, key);
            return new SetupIntentResult(si.id(), si.clientSecret(), si.status());
        });
    }

    @Override
    public EphemeralKeyResult createEphemeralKey(String stripeVersion, String idempotencyKey) {
        String customerId = access.customer().subject();
        String version = stripeVersion == null || stripeVersion.isBlank() ? DEFAULT_EPHEMERAL_KEY_VERSION
                : stripeVersion.trim();
        if (!version.matches("\\d{4}-\\d{2}-\\d{2}(\\.[a-z]+)?")) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Unknown Stripe API version",
                    Map.of("stripeVersion", "Expected a Stripe API version such as 2025-08-27.basil"));
        }
        return tx.serializable(() -> {
            StripeCustomer customer = ensureCustomer(customerId);
            String key = "ephemeral-key:" + customerId + ":" + (idempotencyKey == null || idempotencyKey.isBlank()
                    ? UUID.randomUUID() : idempotencyKey);
            StripeGateway.EphemeralKeySnapshot k = stripe.createEphemeralKey(customer.stripeCustomerId(), version,
                    key);
            return new EphemeralKeyResult(customer.stripeCustomerId(), k.secret(), k.expiresAt());
        });
    }

    @Override
    public WalletMethod setDefault(String paymentMethodId) {
        String customerId = access.customer().subject();
        return tx.serializable(() -> {
            StripeCustomer customer = wallet.lockCustomer(customerId).orElseThrow(() -> notFound(paymentMethodId));
            SavedPaymentMethod method = owned(customerId, paymentMethodId);
            if (!method.verified()) {
                throw new PaymentsStateConflict("payments.method-unverified",
                        "This bank account is pending verification and cannot be the default yet");
            }
            if (!paymentMethodId.equals(customer.defaultPaymentMethodId())) {
                sync.makeDefault(customer, paymentMethodId);
            }
            return new WalletMethod(method, true);
        });
    }

    @Override
    public void remove(String paymentMethodId) {
        String customerId = access.customer().subject();
        tx.serializable(() -> {
            StripeCustomer customer = wallet.lockCustomer(customerId).orElseThrow(() -> notFound(paymentMethodId));
            owned(customerId, paymentMethodId);
            SavedPaymentMethod.requireRemovable(paymentMethodId, customer.defaultPaymentMethodId());
            stripe.detachPaymentMethod(paymentMethodId, "detach:" + paymentMethodId);
            wallet.markDetached(paymentMethodId, Instant.now(clock));
            return null;
        });
    }

    private StripeCustomer ensureCustomer(String customerId) {
        return sync.ensureCustomer(customerId);
    }

    private SavedPaymentMethod owned(String customerId, String paymentMethodId) {
        return wallet.findMethod(paymentMethodId).filter(m -> m.customerId().equals(customerId))
                .orElseThrow(() -> notFound(paymentMethodId));
    }

    private static List<String> normaliseTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return List.of(PaymentMethodType.CARD.stripe());
        }
        List<String> out = new ArrayList<>();
        for (String t : new LinkedHashSet<>(types)) {
            String code = t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
            if (PaymentMethodType.ofStripe(code).isEmpty()) {
                throw new PaymentsRuleViolation("payments.invalid-method-type", "Unsupported payment method type "
                        + t, Map.of("types", "card, us_bank_account, cashapp or link"));
            }
            out.add(code);
        }
        return out;
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("payments.payment-method-not-found", "No saved payment method " + id);
    }
}
