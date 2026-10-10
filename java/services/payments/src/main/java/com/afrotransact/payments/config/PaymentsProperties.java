package com.afrotransact.payments.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.payments.*} settings. Stripe keys come only from the environment (STRIPE_SECRET_KEY,
 * STRIPE_WEBHOOK_SECRET).
 *
 * @param retailOrgId     AfroTransact Retail organisation (pays no commission)
 * @param settlementHold  settlement hold after delivery / service completion (7 days)
 * @param currency        default currency of balances and reconciliation
 * @param zone            zone of "one payout per day" and of the daily reconciliation window
 */
@ConfigurationProperties(prefix = "at.payments")
public record PaymentsProperties(String retailOrgId, Duration settlementHold, String currency, String zone,
                                 Payout payout, Stripe stripe, Webhooks webhooks, Jobs jobs) {

    public PaymentsProperties {
        retailOrgId = retailOrgId == null ? "org_afrotransact_retail" : retailOrgId;
        settlementHold = settlementHold == null ? Duration.ofDays(7) : settlementHold;
        currency = currency == null ? "USD" : currency;
        zone = zone == null ? "UTC" : zone;
        payout = payout == null ? new Payout(null, null) : payout;
        stripe = stripe == null ? new Stripe(null, null, null, null, null, null, null, null) : stripe;
        webhooks = webhooks == null ? new Webhooks(null) : webhooks;
        jobs = jobs == null ? new Jobs(null, null, null, null) : jobs;
    }

    /** Payout rules: minimum (2500 = USD 25.00) and the organisation roles allowed to request. */
    public record Payout(Long minimumMinor, List<String> roles) {

        public Payout {
            minimumMinor = minimumMinor == null ? 2500L : minimumMinor;
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    /**
     * Stripe client.
     *
     * @param apiBase        default https://api.stripe.com (tests: WireMock)
     * @param requireLiveKey prod: only sk_live_/rk_live_ keys; elsewhere live keys are refused
     */
    public record Stripe(String secretKey, String webhookSecret, String apiBase, String connectBase,
                         Boolean requireLiveKey, Integer maxNetworkRetries, Duration connectTimeout,
                         Duration readTimeout) {

        public Stripe {
            apiBase = apiBase == null || apiBase.isBlank() ? "https://api.stripe.com" : apiBase;
            connectBase = connectBase == null || connectBase.isBlank() ? "https://connect.stripe.com" : connectBase;
            requireLiveKey = requireLiveKey != null && requireLiveKey;
            maxNetworkRetries = maxNetworkRetries == null ? 2 : maxNetworkRetries;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        }
    }

    /** Webhook inbox processing. */
    public record Webhooks(Integer maxAttempts) {

        public Webhooks {
            maxAttempts = maxAttempts == null ? 12 : maxAttempts;
        }
    }

    /** Scheduled jobs; intervals and crons are read by the scheduler annotations. */
    public record Jobs(Boolean enabled, Integer webhookBatch, Integer settlementBatch, Duration settlementRecheck) {

        public Jobs {
            enabled = enabled == null || enabled;
            webhookBatch = webhookBatch == null ? 100 : webhookBatch;
            settlementBatch = settlementBatch == null ? 200 : settlementBatch;
            settlementRecheck = settlementRecheck == null ? Duration.ofHours(1) : settlementRecheck;
        }
    }
}
