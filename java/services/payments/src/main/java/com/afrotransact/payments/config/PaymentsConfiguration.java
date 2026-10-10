package com.afrotransact.payments.config;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import com.afrotransact.payments.application.PaymentsSettings;
import com.afrotransact.payments.application.SupplierPaymentSettings;
import com.afrotransact.payments.domain.DefaultPaymentSplitter;
import com.afrotransact.payments.domain.DefaultPayoutPolicy;
import com.afrotransact.payments.domain.DefaultReconciler;
import com.afrotransact.payments.domain.DefaultRefundAllocator;
import com.afrotransact.payments.domain.PaymentSplitter;
import com.afrotransact.payments.domain.PayoutPolicy;
import com.afrotransact.payments.domain.Reconciler;
import com.afrotransact.payments.domain.RefundAllocator;
import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of framework-free domain policies, settings and the Stripe client. */
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

    @Bean
    StripeClient stripeClient(PaymentsProperties props) {
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
