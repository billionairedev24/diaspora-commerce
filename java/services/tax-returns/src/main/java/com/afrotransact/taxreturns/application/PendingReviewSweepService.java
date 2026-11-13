package com.afrotransact.taxreturns.application;

/**
 * Clears the tax review queue of items the rules decide: every record waiting in review that qualifies under the
 * risk-based rule is approved by {@code auto} (see {@link VariantClassificationService#autoApproveIfQualified}). Runs
 * when staff switch the review mode or change the always-review list, and on a schedule, so a record held before the
 * rules were allowed to decide (or whose category was activated later) does not wait for a person it does not need.
 */
public interface PendingReviewSweepService {

    /**
     * One pass over the records waiting in review. Does nothing in mode ALL. Idempotent; a record another writer changed
     * meanwhile is skipped and picked up by the next pass.
     */
    Report sweep();

    /**
     * @param checked  records that were waiting
     * @param approved records approved by this pass
     * @param skipped  records another writer changed meanwhile or that could not be read
     */
    record Report(int checked, int approved, int skipped) {
    }
}
