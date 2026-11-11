package com.afrotransact.taxreturns.domain.profile;

/**
 * How variant classifications reach the admin tax review (setting {@code taxReview.mode}). ALL is the design default and
 * today's behaviour: every classification waits for a reviewer. RISK_BASED may be switched on only after the Texas tax
 * professional agrees (docs/engineering/listing-rules.md decision D7).
 */
public enum ReviewMode {
    ALL, RISK_BASED
}
