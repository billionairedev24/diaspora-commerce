package com.afrotransact.trustops.config;

import java.time.Duration;
import java.util.List;

import com.afrotransact.trustops.domain.SlaTargets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.trustops.*}.
 *
 * @param sla          SLA targets of the queues
 * @param client       client-credentials client used for every service-to-service call
 * @param catalog      catalog-service (moderation decisions)
 * @param payments     payments-service (claim refunds)
 * @param seller       seller-service (payout holds)
 * @param offers       offers-service (seller performance for the buy box)
 * @param ai           ai-gateway triage (optional, fail-open)
 * @param audit        audit log ingestion and export
 * @param sellerHealth seller-health job
 * @param jobs         SLA sweep
 * @param orders       orders-service (order lookup for claims)
 * @param claims       claim ownership checks
 * @param bookings     bookings-service (review moderation decisions)
 * @param retailOrgId  AfroTransact Retail (1P merchant of record): never suspended, no payouts to hold
 */
@ConfigurationProperties(prefix = "at.trustops")
public record TrustOpsProperties(Sla sla, Client client, Upstream catalog, Upstream payments, Upstream seller,
                                 Upstream offers, Ai ai, Audit audit, SellerHealth sellerHealth, Jobs jobs,
                                 Upstream orders, Claims claims, Upstream bookings, String retailOrgId) {

    public TrustOpsProperties {
        sla = sla == null ? new Sla(null, null, null, null, null, null, null, null) : sla;
        client = client == null ? new Client(null, null, null, null, null) : client;
        catalog = catalog == null ? new Upstream("http://localhost:8101") : catalog;
        payments = payments == null ? new Upstream("http://localhost:8105") : payments;
        seller = seller == null ? new Upstream("http://localhost:8106") : seller;
        offers = offers == null ? new Upstream("http://localhost:8102") : offers;
        ai = ai == null ? new Ai(null, null, null) : ai;
        audit = audit == null ? new Audit(null, null, null, null) : audit;
        sellerHealth = sellerHealth == null ? new SellerHealth(null, null, null, null, null) : sellerHealth;
        jobs = jobs == null ? new Jobs(null) : jobs;
        orders = orders == null ? new Upstream("http://localhost:8104") : orders;
        claims = claims == null ? new Claims(null) : claims;
        bookings = bookings == null ? new Upstream("http://localhost:8109") : bookings;
        retailOrgId = retailOrgId == null || retailOrgId.isBlank() ? "org_afrotransact_retail" : retailOrgId.trim();
    }

    /**
     * Claims.
     *
     * @param verifyOwnership check the claimant, shipment and seller against orders-service's order lookup (default
     *                        true; off only where orders-service is not reachable, e.g. isolated demos)
     */
    public record Claims(Boolean verifyOwnership) {

        public Claims {
            verifyOwnership = verifyOwnership == null ? Boolean.TRUE : verifyOwnership;
        }
    }

    /**
     * SLA targets; unset values use {@link SlaTargets#defaults()}.
     *
     * @param sellerResponseReminder lead time of the seller's claim reminder (default 24 h before the deadline)
     */
    public record Sla(Duration moderation, Duration moderationHighRisk, Duration moderationEscalated,
                      Duration sellerResponse, Duration claimDecision, Duration claimEscalated,
                      Duration supportFirstResponse, Duration sellerResponseReminder) {

        public Sla {
            sellerResponseReminder = sellerResponseReminder == null ? Duration.ofHours(24) : sellerResponseReminder;
        }

        public SlaTargets targets() {
            SlaTargets d = SlaTargets.defaults();
            return new SlaTargets(or(moderation, d.moderation()), or(moderationHighRisk, d.moderationHighRisk()),
                    or(moderationEscalated, d.moderationEscalated()), or(sellerResponse, d.sellerResponse()),
                    or(claimDecision, d.claimDecision()), or(claimEscalated, d.claimEscalated()),
                    or(supportFirstResponse, d.supportFirstResponse()));
        }

        private static Duration or(Duration value, Duration fallback) {
            return value == null ? fallback : value;
        }
    }

    /**
     * OAuth2 client credentials.
     *
     * @param clientSecret blank = call without a token (local profile, where the other services run without security)
     * @param scopes       space-separated scopes requested for the token
     */
    public record Client(String tokenUri, String clientId, String clientSecret, String scopes, Duration timeout) {

        public Client {
            tokenUri = tokenUri == null ? "http://localhost:8100/oauth2/token" : tokenUri;
            clientId = clientId == null ? "trust-ops-service" : clientId;
            clientSecret = clientSecret == null ? "" : clientSecret;
            scopes = scopes == null
                    ? "catalog:moderate payments:service sellers:enforce offers:write ai:triage orders:lookup "
                    + "bookings:reviews:moderate" : scopes;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /** Base URL of another service. */
    public record Upstream(String baseUrl) {
    }

    /** AI triage hints: off, or on with a short timeout (moderation never waits long for AI). */
    public record Ai(Boolean enabled, String baseUrl, Duration timeout) {

        public Ai {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            baseUrl = baseUrl == null ? "http://localhost:8113" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }
    }

    /**
     * @param topics        topics consumed into the audit log
     * @param eventTypes    event types kept (others on those topics are ignored)
     * @param streamEnabled publish {@code trustops.audit.recorded} per entry
     * @param exportMaxRows CSV export cap
     */
    public record Audit(List<String> topics, List<String> eventTypes, Boolean streamEnabled, Integer exportMaxRows) {

        public Audit {
            topics = topics == null ? List.of() : List.copyOf(topics);
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
            streamEnabled = streamEnabled == null ? Boolean.TRUE : streamEnabled;
            exportMaxRows = exportMaxRows == null ? 50_000 : exportMaxRows;
        }
    }

    /** Rolling window, push switch and targets of the seller-health job. */
    public record SellerHealth(Duration window, Boolean pushToOffers, Double lateShipmentThreshold,
                               Double cancellationThreshold, Double defectThreshold) {

        public SellerHealth {
            window = window == null ? Duration.ofDays(90) : window;
            pushToOffers = pushToOffers == null ? Boolean.TRUE : pushToOffers;
            lateShipmentThreshold = lateShipmentThreshold == null ? 0.04 : lateShipmentThreshold;
            cancellationThreshold = cancellationThreshold == null ? 0.025 : cancellationThreshold;
            defectThreshold = defectThreshold == null ? 0.01 : defectThreshold;
        }
    }

    /** Background jobs (SLA sweep, daily seller health). */
    public record Jobs(Boolean enabled) {

        public Jobs {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }
    }
}
