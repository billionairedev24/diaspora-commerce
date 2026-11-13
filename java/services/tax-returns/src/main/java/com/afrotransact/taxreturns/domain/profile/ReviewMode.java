package com.afrotransact.taxreturns.domain.profile;

/**
 * How variant classifications reach the admin tax review (setting {@code taxReview.mode}). RISK_BASED is the default
 * when staff stored no value: the rules decide, and a person looks only at conflicts, no matching rule, missing
 * evidence, gated category statuses and the regulated always-review list (docs/engineering/listing-rules.md D7). ALL
 * sends every classification to a person; staff may choose it at any time.
 */
public enum ReviewMode {
    ALL, RISK_BASED
}
