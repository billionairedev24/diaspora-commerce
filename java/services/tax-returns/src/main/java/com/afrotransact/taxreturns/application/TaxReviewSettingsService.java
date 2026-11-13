package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.taxreturns.domain.profile.ReviewMode;
import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.domain.profile.ReviewSettingsChange;

/**
 * The tax review settings (docs/engineering/listing-rules.md D7): {@code taxReview.mode} RISK_BASED (default: the rules
 * decide, a person reviews only exceptions) or ALL (every classification waits for a reviewer), the always-review
 * category list and the audit sampling percentage. A change needs a reason and is recorded; switching to RISK_BASED also
 * approves the records already waiting that the rules decide.
 */
public interface TaxReviewSettingsService {

    /** The settings in force, who set them and the history (tax:read). */
    ReviewSettingsView current();

    /**
     * Replaces the settings (tax:classify-admin). A null list or percentage keeps the current value. 422
     * tax.reason-required without a reason of 10+ characters, tax.unknown-category for a category without a profile,
     * tax.invalid-audit-percent outside 0 to 100. It applies to new classification events; records already decided keep
     * their status.
     */
    ReviewSettingsView change(ReviewMode mode, List<String> alwaysReview, Integer auditPercent, String reason);

    /**
     * @param settings        the settings in force
     * @param alwaysReviewAlso statuses that always go to review whatever the list says
     * @param changedBy       who set them last, null when never changed
     * @param changedAt       when
     * @param history         newest first
     */
    record ReviewSettingsView(ReviewSettings settings, List<String> alwaysReviewAlso, String changedBy,
                              Instant changedAt, List<ReviewSettingsChange> history) {
    }
}
