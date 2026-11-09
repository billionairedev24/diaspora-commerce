package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.springframework.stereotype.Component;

/** Publishes {@code tax.classification.changed} (call inside the transaction of the change; outbox). */
@Component
class ClassificationEvents {

    private final DomainEventPublisher events;
    private final TaxTelemetry telemetry;

    ClassificationEvents(DomainEventPublisher events, TaxTelemetry telemetry) {
        this.events = events;
        this.telemetry = telemetry;
    }

    void profileStatus(String categoryCode, String status, String previous, String ruleVersion, String reason, String by,
                       Instant at) {
        Map<String, Object> p = base("PROFILE_STATUS", ruleVersion, reason, by, at);
        p.put("categoryCode", categoryCode);
        p.put("status", status);
        p.put("previousStatus", previous);
        publish(categoryCode, "PROFILE_STATUS", p);
    }

    void providerCode(String categoryCode, String internalTaxCode, TaxProviderId provider, String ruleVersion,
                      String reason, String by, Instant at) {
        Map<String, Object> p = base("PROVIDER_CODE_MAP", ruleVersion, reason, by, at);
        p.put("categoryCode", categoryCode);
        p.put("internalTaxCode", internalTaxCode);
        p.put("provider", provider.name());
        publish(categoryCode, "PROVIDER_CODE_MAP", p);
    }

    void variant(VariantClassification v, String previousStatus, Resolution r, String reason, String by, Instant at) {
        Map<String, Object> p = base("VARIANT_CLASSIFICATION", r == null || r.ruleVersion() == null ? v.ruleVersion()
                : r.ruleVersion(), reason, by, at);
        p.put("categoryCode", v.categoryCode());
        p.put("atin", v.atin());
        p.put("productId", v.productId() == null ? null : v.productId().toString());
        p.put("status", v.status().name());
        p.put("previousStatus", previousStatus);
        p.put("outcome", r == null || r.outcome() == null ? null : r.outcome().name());
        p.put("internalTaxCode", r == null ? null : r.internalTaxCode());
        publish(v.atin(), "VARIANT_CLASSIFICATION", p);
    }

    private void publish(String key, String kind, Map<String, Object> payload) {
        events.publish(TaxReturnsEvents.TAX_CLASSIFICATION_CHANGED, TaxReturnsEvents.AGGREGATE, key, payload);
        telemetry.recordChange(kind);
    }

    private static Map<String, Object> base(String kind, String ruleVersion, String reason, String by, Instant at) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", kind);
        p.put("ruleVersion", ruleVersion);
        p.put("reason", reason);
        p.put("changedBy", by);
        p.put("occurredAt", at);
        return p;
    }
}
