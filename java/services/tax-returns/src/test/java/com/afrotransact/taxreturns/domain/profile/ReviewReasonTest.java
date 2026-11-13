package com.afrotransact.taxreturns.domain.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Why a record needs a person is read from its state and last resolution, never guessed. */
class ReviewReasonTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    private static Resolution resolution(ResolveStatus status, List<MissingFact> missing) {
        return new Resolution(status, null, null, Map.of(), null, null, missing, "tx-1", "x", 0);
    }

    private static VariantClassification record(RecordStatus status, Resolution resolution, Instant auditSampledAt) {
        return new VariantClassification("AT00000001", null, UUID.randomUUID(), "Roasted cashews", "org_a", "nuts-seeds",
                "snack", Map.of(), Map.of(), Map.of(), Map.of(), status, resolution, "tx-1", "seller_1", NOW, null, null,
                null, null, auditSampledAt, 0L, NOW, NOW);
    }

    private static final MissingFact A_MISSING_FACT = new MissingFact("net_weight_oz", "Net weight", "Needed");

    @Test
    void anOpenDecisionOnTheCategoryIsAConflict() {
        assertThat(ReviewReason.of(record(RecordStatus.PENDING_REVIEW, resolution(ResolveStatus.CONFLICT_OPEN, List.of()), null)))
                .isEqualTo(ReviewReason.CONFLICT);
    }

    @Test
    void aCategoryThatNeedsSignOffIsRegulated() {
        assertThat(ReviewReason.of(record(RecordStatus.PENDING_REVIEW, resolution(ResolveStatus.NEEDS_APPROVAL, List.of()), null)))
                .isEqualTo(ReviewReason.REGULATED_CATEGORY);
    }

    @Test
    void missingFactsWithoutAnyListedFactMeansNoRuleMatched() {
        assertThat(ReviewReason.of(record(RecordStatus.PENDING_REVIEW, resolution(ResolveStatus.MISSING_FACTS, List.of()), null)))
                .isEqualTo(ReviewReason.NO_MATCHING_RULE);
    }

    @Test
    void missingFactsWithAListedFactIsAMissingFact() {
        assertThat(ReviewReason.of(record(RecordStatus.MISSING_FACTS,
                resolution(ResolveStatus.MISSING_FACTS, List.of(A_MISSING_FACT)), null))).isEqualTo(ReviewReason.MISSING_FACT);
    }

    @Test
    void aCategoryWithoutAProfileMatchesNoRule() {
        assertThat(ReviewReason.of(record(RecordStatus.PENDING_REVIEW, null, null))).isEqualTo(ReviewReason.NO_MATCHING_RULE);
    }

    @Test
    void aGenericProviderCodeAndABlockedBundleHaveTheirOwnReasons() {
        assertThat(ReviewReason.of(record(RecordStatus.PENDING_REVIEW, resolution(ResolveStatus.REFINE_CODE, List.of()), null)))
                .isEqualTo(ReviewReason.CODE_TO_VERIFY);
        assertThat(ReviewReason.of(record(RecordStatus.PENDING_REVIEW, resolution(ResolveStatus.BLOCKED, List.of()), null)))
                .isEqualTo(ReviewReason.BLOCKED);
    }

    @Test
    void aResolvedWaitingRecordIsANewListing() {
        assertThat(ReviewReason.of(record(RecordStatus.PENDING_REVIEW, resolution(ResolveStatus.RESOLVED, List.of()), null)))
                .isEqualTo(ReviewReason.NEW_LISTING);
    }

    @Test
    void staleAndRejectedRecordsSayWhy() {
        assertThat(ReviewReason.of(record(RecordStatus.STALE, resolution(ResolveStatus.RESOLVED, List.of()), null)))
                .isEqualTo(ReviewReason.CHANGED);
        assertThat(ReviewReason.of(record(RecordStatus.REJECTED, resolution(ResolveStatus.RESOLVED, List.of()), null)))
                .isEqualTo(ReviewReason.WITH_SELLER);
    }

    @Test
    void anApprovedRecordNeedsAPersonOnlyInTheAuditSample() {
        Resolution resolved = resolution(ResolveStatus.RESOLVED, List.of());
        assertThat(ReviewReason.of(record(RecordStatus.APPROVED, resolved, NOW))).isEqualTo(ReviewReason.AUDIT_SAMPLE);
        assertThat(ReviewReason.of(record(RecordStatus.APPROVED, resolved, null))).isNull();
    }
}
