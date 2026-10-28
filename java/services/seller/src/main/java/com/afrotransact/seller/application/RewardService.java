package com.afrotransact.seller.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.RewardComparison;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.RewardProgress;
import com.afrotransact.seller.domain.RewardRule;
import com.afrotransact.seller.domain.RewardRuleInput;
import com.afrotransact.seller.domain.RewardRuleStatus;

/**
 * Reward rules (staff with plans:manage) and their evaluation: event-driven for metrics with events (sales, orders,
 * bookings, listings) and daily for every rule. A qualifying organisation gets one grant per rule (unique per rule
 * and organisation, counted against the rule's max grants), applied at Stripe and notified.
 *
 * <p>S299: rules checked at window end (LESS_THAN, or AT_LEAST with checkAtWindowEnd) are never decided by events:
 * {@link #evaluateWindowEnds()} (every 15 minutes) decides each once per (rule, organisation, window) when its check is
 * due — the lead before the window closes, or before the renewal when Stripe would invoice first.
 */
public interface RewardService {

    /** A reward or credit the organisation received, with its rule's comparison (null for staff adjustments). */
    record Earned(BillingGrant grant, RewardComparison comparison) {
    }

    List<RewardRule> rules();

    RewardRule rule(UUID id);

    RewardRule create(RewardRuleInput in);

    /** {@code expectedVersion} is the rule's version (optimistic locking). */
    RewardRule update(UUID id, RewardRuleInput in, long expectedVersion);

    RewardRule setStatus(UUID id, RewardRuleStatus status);

    /** Who qualifies now under {@code in} (an unsaved or edited rule; {@code ruleId} marks who already got it). */
    RewardPreview preview(RewardRuleInput in, UUID ruleId);

    /** Seller Center: rules the organisation can still earn, with progress (owner / finance, staff). */
    List<RewardProgress> progress(String orgId);

    /** Seller Center: rewards and credits the organisation received. */
    List<Earned> earned(String orgId);

    /** Evaluates the organisation's live rules on {@code metric} (null = every metric); returns grants made. */
    int evaluate(String orgId, RewardMetric metric);

    /** Daily job: every organisation, every live rule. Returns grants made. */
    int evaluateAll();

    /** Window-end job: every organisation, the live rules checked at window end whose check is due. */
    int evaluateWindowEnds();
}
