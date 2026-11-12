package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.catalog.domain.GtinExemptionRequest;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code catalog.change_request.*} and {@code catalog.gtin_exemption.*} (topic at.change-request.events, key =
 * request id). Decided events are the audit trail: who decided, what, why. Call inside the transaction of the change.
 */
@Component
class ChangeRequestEvents {

    private final DomainEventPublisher events;

    ChangeRequestEvents(DomainEventPublisher events) {
        this.events = events;
    }

    void created(ProductChangeRequest r, Product page, Instant now) {
        events.publish(CatalogEvents.CHANGE_REQUEST_CREATED, CatalogEvents.AGGREGATE_CHANGE_REQUEST, r.id().toString(),
                changeRequest(r, page, null, now));
    }

    void decided(ProductChangeRequest r, Product page, Long pageVersion, Instant now) {
        events.publish(CatalogEvents.CHANGE_REQUEST_DECIDED, CatalogEvents.AGGREGATE_CHANGE_REQUEST, r.id().toString(),
                changeRequest(r, page, pageVersion, now));
    }

    void exemptionRequested(GtinExemptionRequest r, Instant now) {
        events.publish(CatalogEvents.GTIN_EXEMPTION_REQUESTED, CatalogEvents.AGGREGATE_CHANGE_REQUEST, r.id().toString(),
                exemption(r, now));
    }

    void exemptionDecided(GtinExemptionRequest r, Instant now) {
        events.publish(CatalogEvents.GTIN_EXEMPTION_DECIDED, CatalogEvents.AGGREGATE_CHANGE_REQUEST, r.id().toString(),
                exemption(r, now));
    }

    private static Map<String, Object> changeRequest(ProductChangeRequest r, Product page, Long pageVersion, Instant now) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("changeRequestId", r.id().toString());
        p.put("atin", r.atin());
        p.put("productId", r.productId().toString());
        p.put("pageOwnerOrgId", page.owner().orgId());
        p.put("type", r.type().name());
        p.put("field", r.field().isEmpty() ? null : r.field());
        p.put("status", r.status().name());
        p.put("requestedByOrg", r.requestedByOrg());
        p.put("decidedBy", r.decidedBy());
        p.put("reason", r.reason());
        p.put("resolution", r.resolution());
        p.put("pageVersion", pageVersion);
        p.put("occurredAt", now.toString());
        return p;
    }

    private static Map<String, Object> exemption(GtinExemptionRequest r, Instant now) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("exemptionId", r.id().toString());
        p.put("productId", r.productId().toString());
        p.put("productAtin", r.productAtin());
        p.put("requiredBecause", r.requiredBecause().name());
        p.put("status", r.status().name());
        p.put("requestedByOrg", r.requestedByOrg());
        p.put("decidedBy", r.decidedBy());
        p.put("reason", r.reason());
        p.put("occurredAt", now.toString());
        return p;
    }
}
