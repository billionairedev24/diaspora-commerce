package com.afrotransact.taxreturns.domain.profile;

/**
 * The outcome of the risk-based review rule for one classification.
 *
 * @param autoApprove true when the system may approve it without a person
 * @param rule        plain statement of the rule that applied, for the audit trail and the reviewer
 */
public record RiskReviewDecision(boolean autoApprove, String rule) {
}
