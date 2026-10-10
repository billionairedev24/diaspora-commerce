package com.afrotransact.trustops.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Business settings (from {@code at.trustops.*}).
 *
 * @param auditEventTypes  consumed event types copied into the audit log
 * @param auditStream      publish {@code trustops.audit.recorded} for every appended entry
 * @param exportMaxRows    CSV export cap
 * @param healthWindow     rolling window of seller-health metrics
 * @param pushToOffers     push computed performance to offers-service
 */
public record TrustOpsSettings(Set<String> auditEventTypes, boolean auditStream, int exportMaxRows,
                               Duration healthWindow, boolean pushToOffers) {

    public TrustOpsSettings {
        auditEventTypes = Set.copyOf(auditEventTypes);
        Objects.requireNonNull(healthWindow, "healthWindow");
    }
}
