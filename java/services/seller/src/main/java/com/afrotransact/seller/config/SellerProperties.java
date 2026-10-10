package com.afrotransact.seller.config;

import java.time.Duration;
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
 * @param metering      usage sources of metered billing (S206) and the seller-service client credentials
 * @param retailOrgId   AfroTransact Retail (1P merchant of record): no plan, no metered billing, no suspension
 * @param geo           geo-service (geocoding ship-from addresses, delivery zones for AfroTransact pickup, S255)
 */
@ConfigurationProperties(prefix = "at.seller")
public record SellerProperties(List<String> liveCountries, Stripe stripe, Crypto crypto, Media media, Jobs jobs,
                               Metering metering, String retailOrgId, Geo geo) {

    public SellerProperties {
        liveCountries = liveCountries == null || liveCountries.isEmpty() ? List.of("US") : List.copyOf(liveCountries);
        stripe = stripe == null ? new Stripe(null, null, null, null, null, null, null) : stripe;
        crypto = crypto == null ? new Crypto(null, null) : crypto;
        media = media == null ? new Media(null, null) : media;
        jobs = jobs == null ? new Jobs(null) : jobs;
        metering = metering == null ? new Metering(null, null, null, null, null, null, null) : metering;
        retailOrgId = retailOrgId == null || retailOrgId.isBlank() ? "org_afrotransact_retail" : retailOrgId.trim();
        geo = geo == null ? new Geo(null, null, null) : geo;
    }

    /**
     * geo-service client and the AfroTransact pickup rule.
     *
     * @param pickupMaxRadiusMiles pickup locations must be inside an active DELIVERY zone or at most this far from one
     */
    public record Geo(String baseUrl, Duration timeout, Double pickupMaxRadiusMiles) {

        public Geo {
            baseUrl = baseUrl == null ? "http://localhost:8205" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
            pickupMaxRadiusMiles = pickupMaxRadiusMiles == null ? 25.0 : pickupMaxRadiusMiles;
        }
    }

    /** Usage sources (inventory, content, ai-gateway) called with the seller-service client-credentials token. */
    public record Metering(String inventoryUrl, String contentUrl, String aiUrl, String tokenUri, String clientId,
                           String clientSecret, Duration timeout) {

        public Metering {
            inventoryUrl = inventoryUrl == null ? "http://localhost:8103" : inventoryUrl;
            contentUrl = contentUrl == null ? "http://localhost:8112" : contentUrl;
            aiUrl = aiUrl == null ? "http://localhost:8113" : aiUrl;
            tokenUri = tokenUri == null ? "http://localhost:8100/oauth2/token" : tokenUri;
            clientId = clientId == null ? "seller-service" : clientId;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
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
