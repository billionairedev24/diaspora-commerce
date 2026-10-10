package com.afrotransact.seller.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.seller.*} settings.
 *
 * @param liveCountries markets open to sellers (ISO codes; US only by default)
 * @param stripe        Stripe API key, optional API/files base URLs (tests point them at WireMock), webhook secrets
 * @param crypto        field-encryption keys (key id → base64 32-byte AES key) and the active key id
 * @param media         media-service base URL and service token
 * @param jobs          scheduled jobs switch
 */
@ConfigurationProperties(prefix = "at.seller")
public record SellerProperties(List<String> liveCountries, Stripe stripe, Crypto crypto, Media media, Jobs jobs) {

    public SellerProperties {
        liveCountries = liveCountries == null || liveCountries.isEmpty() ? List.of("US") : List.copyOf(liveCountries);
        stripe = stripe == null ? new Stripe(null, null, null, null, null, null, null) : stripe;
        crypto = crypto == null ? new Crypto(null, null) : crypto;
        media = media == null ? new Media(null, null) : media;
        jobs = jobs == null ? new Jobs(null) : jobs;
    }

    /** Stripe settings. Secrets only from env (STRIPE_SECRET_KEY, STRIPE_*_WEBHOOK_SECRET). */
    public record Stripe(String secretKey, String apiBase, String filesBase, String connectWebhookSecret,
                         String billingWebhookSecret, List<String> sessionComponents, Integer maxNetworkRetries) {

        public Stripe {
            secretKey = secretKey == null ? "" : secretKey;
            sessionComponents = sessionComponents == null || sessionComponents.isEmpty()
                    ? List.of("account_onboarding", "account_management", "notification_banner")
                    : List.copyOf(sessionComponents);
            maxNetworkRetries = maxNetworkRetries == null ? 2 : maxNetworkRetries;
        }
    }

    /** Field-level encryption (FIELD_ENCRYPTION_KEY → keys.k1). */
    public record Crypto(String activeKeyId, Map<String, String> keys) {

        public Crypto {
            activeKeyId = activeKeyId == null ? "k1" : activeKeyId;
            keys = keys == null ? Map.of() : Map.copyOf(keys);
        }
    }

    /** media-service client. */
    public record Media(String baseUrl, String serviceToken) {

        public Media {
            baseUrl = baseUrl == null ? "http://localhost:8207" : baseUrl;
        }
    }

    /** Job switches; schedules are read by the scheduler annotations. */
    public record Jobs(Boolean enabled) {

        public Jobs {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }
    }
}
