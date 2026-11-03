package com.afrotransact.seller.domain;

import java.util.List;
import java.util.UUID;

/**
 * The trial a new subscription gets.
 *
 * @param campaignId the campaign whose trial was used (redeemed at activation), else null
 */
public record TrialDecision(int days, TrialSource source, UUID campaignId) {

    /**
     * A per-seller override (staff, with a reason) wins, even when shorter; otherwise the longest of the plan's
     * default and the matching campaigns. Free plans have no trial.
     */
    public static TrialDecision decide(Plan plan, Integer overrideDays, List<TrialCampaign> matching) {
        if (plan.isFree()) {
            return new TrialDecision(0, TrialSource.NONE, null);
        }
        if (overrideDays != null) {
            return new TrialDecision(overrideDays, TrialSource.OVERRIDE, null);
        }
        TrialDecision best = new TrialDecision(plan.trialDays(), plan.trialDays() > 0 ? TrialSource.PLAN
                : TrialSource.NONE, null);
        for (TrialCampaign c : matching) {
            if (c.trialDays() > best.days()) {
                best = new TrialDecision(c.trialDays(), TrialSource.CAMPAIGN, c.id());
            }
        }
        return best;
    }
}
