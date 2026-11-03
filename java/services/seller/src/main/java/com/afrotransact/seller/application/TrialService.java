package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.CampaignInput;
import com.afrotransact.seller.domain.CampaignStatus;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.TrialCampaign;
import com.afrotransact.seller.domain.TrialDecision;

/**
 * Trials: the plan's default, campaigns (a promo code and/or a sign-up window, staff with plans:manage) and the
 * per-seller override (see {@link BillingAdjustmentService#setTrial}). The chosen length becomes the Stripe
 * subscription's {@code trial_end}.
 */
public interface TrialService {

    List<TrialCampaign> campaigns();

    TrialCampaign create(CampaignInput in);

    /** {@code expectedVersion} is the campaign's version (optimistic locking). */
    TrialCampaign update(UUID id, CampaignInput in, long expectedVersion);

    TrialCampaign setStatus(UUID id, CampaignStatus status);

    /** The trial for a sign-up at {@code signupAt} (no access check; used by activation and onboarding). */
    TrialDecision decide(Plan plan, OrgKind kind, Instant signupAt, String promoCode, Integer overrideDays);

    /** Counts a redemption at activation; false when the campaign is full (the trial is kept anyway). */
    boolean redeem(UUID campaignId);

    /** The campaign a promo code names, if it applies to this sign-up. */
    Optional<TrialCampaign> promo(String code, OrgKind kind, String planCode, Instant at);
}
