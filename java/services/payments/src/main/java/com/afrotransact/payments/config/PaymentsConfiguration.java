package com.afrotransact.payments.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import com.afrotransact.payments.adapter.out.localtest.LocalTestStripeGateway;
import com.afrotransact.payments.adapter.out.persistence.HmacGiftCodeHasher;
import com.afrotransact.payments.adapter.out.persistence.HmacSignalHasher;
import com.afrotransact.payments.adapter.out.stripe.StripeJavaGateway;
import com.afrotransact.payments.application.GiftCardSettings;
import com.afrotransact.payments.application.PaymentsSettings;
import com.afrotransact.payments.application.SupplierPaymentSettings;
import com.afrotransact.payments.domain.DefaultLocalTestPaymentsPolicy;
import com.afrotransact.payments.domain.DefaultPaymentSplitter;
import com.afrotransact.payments.domain.DefaultPayoutPolicy;
import com.afrotransact.payments.domain.DefaultReconciler;
import com.afrotransact.payments.domain.DefaultRefundAllocator;
import com.afrotransact.payments.domain.LocalTestPaymentsPolicy;
import com.afrotransact.payments.domain.PaymentSplitter;
import com.afrotransact.payments.domain.PayoutPolicy;
import com.afrotransact.payments.domain.Reconciler;
import com.afrotransact.payments.domain.RefundAllocator;
import com.afrotransact.payments.port.GiftCodeHasher;
import com.afrotransact.payments.port.SignalHasher;
import com.afrotransact.payments.port.StripeGateway;
import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit wiring of framework-free domain policies, settings and the Stripe gateway. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentsProperties.class)
class PaymentsConfiguration {

    @Bean
    PaymentSplitter paymentSplitter(PaymentsProperties props) {
        return new DefaultPaymentSplitter(props.retailOrgId());
    }

    @Bean
    PayoutPolicy payoutPolicy(PaymentsProperties props) {
        Set<String> roles = new HashSet<>(DefaultPayoutPolicy.DEFAULT_ROLES);
        roles.addAll(props.payout().roles());
        return new DefaultPayoutPolicy(props.payout().minimumMinor(), roles);
    }

    @Bean
    RefundAllocator refundAllocator() {
        return new DefaultRefundAllocator();
    }

    @Bean
    Reconciler reconciler() {
        return new DefaultReconciler();
    }

    @Bean
    PaymentsSettings paymentsSettings(PaymentsProperties props) {
        return new PaymentsSettings(props.retailOrgId(), props.settlementHold(), ZoneId.of(props.zone()),
                props.currency(), props.webhooks().maxAttempts(), props.jobs().settlementRecheck());
    }

    /** Net terms for AfroTransact Retail supplier payments (S226), {@code SUPPLIER_PAYMENT_TERMS_DAYS} (30). */
    @Bean
    SupplierPaymentSettings supplierPaymentSettings(
            @Value("${at.payments.supplier-payments.terms-days:30}") int termsDays) {
        return new SupplierPaymentSettings(termsDays);
    }

    /**
     * Gift card redeem limits (S321): {@code at.payments.gift-cards.*}. A customer may get five codes wrong and a
     * network address twenty in 15 minutes.
     */
    @Bean
    GiftCardSettings giftCardSettings(
            @Value("${at.payments.gift-cards.max-failures:5}") int maxFailures,
            @Value("${at.payments.gift-cards.max-failures-per-address:20}") int maxFailuresAddress,
            @Value("${at.payments.gift-cards.window:15m}") Duration window,
            @Value("${at.payments.gift-cards.delivery-batch:50}") int deliveryBatch) {
        return new GiftCardSettings(maxFailures, maxFailuresAddress, window, deliveryBatch);
    }

    /**
     * Gift card codes are stored as HMACs under {@code GIFT_CARD_CODE_KEY}, a secret of the environment. Until it is
     * set the key is derived from the Stripe webhook secret (which every environment has), so a missing variable never
     * stops the service nor leaves a guessable key.
     */
    @Bean
    GiftCodeHasher giftCodeHasher(@Value("${at.payments.gift-cards.code-key:}") String codeKey,
                                  PaymentsProperties props) {
        String key = codeKey == null || codeKey.isBlank()
                ? "gift-card-code-key:" + props.stripe().webhookSecret() : codeKey;
        return new HmacGiftCodeHasher(key);
    }

    /**
     * Referral abuse-check fingerprints (S322) are stored as HMACs under {@code REFERRAL_SIGNAL_KEY}, derived from the
     * Stripe webhook secret until it is set (same approach as the gift card key).
     */
    @Bean
    SignalHasher signalHasher(@Value("${at.payments.referral.signal-key:}") String signalKey,
                              PaymentsProperties props) {
        String key = signalKey == null || signalKey.isBlank()
                ? "referral-signal-key:" + props.stripe().webhookSecret() : signalKey;
        return new HmacSignalHasher(key);
    }

    @Bean
    LocalTestPaymentsPolicy localTestPaymentsPolicy() {
        return new DefaultLocalTestPaymentsPolicy();
    }

    /**
     * Stripe, or on the local profile without a Stripe key the local test stand-in (LocalTestPaymentsPolicy). Asking
     * for the stand-in anywhere else stops the service at start-up.
     */
    @Bean
    StripeGateway stripeGateway(PaymentsProperties props, LocalTestPaymentsPolicy policy, Environment env) {
        if (useLocalTest(policy, props.stripe(), activeProfiles(env))) {
            return new LocalTestStripeGateway();
        }
        return new StripeJavaGateway(stripeClient(props), props.stripe().multicapture());
    }

    /** Active profiles, or the default profile when none is active. */
    static Set<String> activeProfiles(Environment env) {
        String[] active = env.getActiveProfiles();
        return new LinkedHashSet<>(Arrays.asList(active.length > 0 ? active : env.getDefaultProfiles()));
    }

    /**
     * True when the configuration asks for the local stand-in and the profiles allow it.
     *
     * @throws IllegalStateException when it is asked for outside the local profile (dev, uat, prod, …)
     */
    static boolean useLocalTest(LocalTestPaymentsPolicy policy, PaymentsProperties.Stripe stripe,
                                Set<String> profiles) {
        if (!policy.wantsLocalTest(stripe.mode(), stripe.secretKey())) {
            return false;
        }
        if (!policy.localTestAllowed(profiles)) {
            throw new IllegalStateException("Payments without Stripe (at.payments.stripe.mode=" + stripe.mode()
                    + ") are for the local profile only (active profiles " + profiles + "); set STRIPE_SECRET_KEY "
                    + "and at.payments.stripe.mode=stripe");
        }
        return true;
    }

    static StripeClient stripeClient(PaymentsProperties props) {
        PaymentsProperties.Stripe s = props.stripe();
        String key = s.secretKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("STRIPE_SECRET_KEY is not set");
        }
        boolean live = key.startsWith("sk_live_") || key.startsWith("rk_live_");
        if (s.requireLiveKey() && !live) {
            throw new IllegalStateException("This environment requires a live Stripe key (sk_live_/rk_live_)");
        }
        if (!s.requireLiveKey() && live) {
            throw new IllegalStateException("Live Stripe keys are only allowed in production; use a test key");
        }
        return StripeClient.builder()
                .setApiKey(key)
                .setApiBase(s.apiBase())
                .setConnectBase(s.connectBase())
                .setMaxNetworkRetries(s.maxNetworkRetries())
                .setConnectTimeout((int) s.connectTimeout().toMillis())
                .setReadTimeout((int) s.readTimeout().toMillis())
                .build();
    }
}
