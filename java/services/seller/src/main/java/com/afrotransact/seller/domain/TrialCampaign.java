package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A trial override for sign-ups in a window ({@code signupFrom}–{@code signupTo}) and/or with a promo code, e.g.
 * "60-day trial for sign-ups in October". Without a promo code it applies to every sign-up in the window; with one,
 * only to applications that entered it.
 *
 * @param eligiblePlans plan codes it applies to (empty = every paid plan)
 */
public record TrialCampaign(UUID id, String name, String promoCode, int trialDays, Instant signupFrom,
                            Instant signupTo, OrgKind kind, List<String> eligiblePlans, Integer maxRedemptions,
                            int redemptions, CampaignStatus status, String createdBy, Instant createdAt,
                            String updatedBy, Instant updatedAt, long version) {

    public TrialCampaign {
        eligiblePlans = eligiblePlans == null ? List.of() : List.copyOf(eligiblePlans);
        promoCode = normalise(promoCode);
    }

    public static String normalise(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    /** Applies to this sign-up (time, kind, plan) and, when it has a promo code, to {@code enteredCode}. */
    public boolean appliesTo(Instant signupAt, OrgKind orgKind, String planCode, String enteredCode) {
        if (status != CampaignStatus.ACTIVE) {
            return false;
        }
        if (maxRedemptions != null && redemptions >= maxRedemptions) {
            return false;
        }
        if (signupFrom != null && signupAt.isBefore(signupFrom)) {
            return false;
        }
        if (signupTo != null && !signupAt.isBefore(signupTo)) {
            return false;
        }
        if (kind != null && kind != orgKind) {
            return false;
        }
        if (!eligiblePlans.isEmpty() && !eligiblePlans.contains(planCode)) {
            return false;
        }
        return promoCode == null || promoCode.equals(normalise(enteredCode));
    }
}
