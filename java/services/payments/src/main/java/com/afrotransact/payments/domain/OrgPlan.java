package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * payments' projection of an organisation's current plan, from the seller service's {@code seller.subscription.changed}
 * events (or, while no event arrived yet, from the seller service's billing API). Only the plan code matters here: it
 * selects the plan rates of the fee schedule.
 *
 * @param planCode   the plan in force, null when the organisation has none (cancelled, not activated)
 * @param status     the subscription status as the seller service reported it
 * @param source     EVENT (seller.subscription.changed) or API (fallback read)
 * @param occurredAt when the seller service recorded the state (newer events win)
 */
public record OrgPlan(String orgId, String planCode, String status, String source, Instant occurredAt) {

    public static final String EVENT = "EVENT";
    public static final String API = "API";

    public OrgPlan {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        planCode = planCode == null || planCode.isBlank() ? null : planCode.trim().toLowerCase(Locale.ROOT);
    }

    /** The plan whose rates apply: none for a cancelled subscription or one that never started. */
    public static String effectivePlan(String planCode, String status) {
        if (status == null) {
            return planCode;
        }
        return switch (status) {
            case "CANCELED", "CANCELLED", "PENDING_ACTIVATION", "INCOMPLETE_EXPIRED" -> null;
            default -> planCode;
        };
    }
}
