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
 * @param responseReminder how long before a claim's seller deadline the sweep sends
 *                         {@code trustops.claim.response_due_soon}
 * @param retailOrgId      AfroTransact Retail, the first-party merchant of record: exempt from seller suspension
 *                         (it has no payouts to hold); its health is still computed for the buy box
 */
public record TrustOpsSettings(Set<String> auditEventTypes, boolean auditStream, int exportMaxRows,
                               Duration healthWindow, boolean pushToOffers, Duration responseReminder,
                               String retailOrgId) {

    public TrustOpsSettings {
        auditEventTypes = Set.copyOf(auditEventTypes);
        Objects.requireNonNull(healthWindow, "healthWindow");
        Objects.requireNonNull(responseReminder, "responseReminder");
        Objects.requireNonNull(retailOrgId, "retailOrgId");
    }

    public boolean isRetail(String orgId) {
        return retailOrgId.equals(orgId);
    }
}
