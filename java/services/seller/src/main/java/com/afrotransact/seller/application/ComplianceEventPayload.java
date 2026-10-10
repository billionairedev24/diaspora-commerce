package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.ConnectAccount;

/**
 * Payload of seller.compliance.changed: notifications-service sends email + SMS + in-app banner (consent and quiet
 * hours respected).
 *
 * @param trigger what caused it: account.updated, answer, payout.failed, external_account.errored, deauthorized …
 */
public record ComplianceEventPayload(String orgId, String accountId, String status, String previousStatus,
                                     List<String> currentlyDue, List<String> pastDue, List<String> eventuallyDue,
                                     List<String> pendingVerification, List<String> newItems,
                                     Instant currentDeadline, String disabledReason, boolean chargesEnabled,
                                     boolean payoutsEnabled, boolean payoutsPaused, String trigger,
                                     List<String> channels, Instant occurredAt) {

    static final List<String> ALL_CHANNELS = List.of("email", "sms", "in_app");

    static ComplianceEventPayload of(ConnectAccount a, String previousStatus, List<String> newItems, String trigger,
                                     Instant at) {
        var r = a.requirements();
        return new ComplianceEventPayload(a.orgId(), a.accountId(), a.status().name(), previousStatus,
                r.currentlyDue(), r.pastDue(), r.eventuallyDue(), r.pendingVerification(), newItems,
                r.currentDeadline(), r.disabledReason(), r.chargesEnabled(), r.payoutsEnabled(), a.payoutsPaused(),
                trigger, ALL_CHANNELS, at);
    }
}
