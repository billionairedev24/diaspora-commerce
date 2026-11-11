package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SellingApplicationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    private static SellingApplication pending() {
        SellingRule rule = new SellingRule(UUID.randomUUID(), SellingScope.category("supplements"), "Supplements",
                SellingLevel.APPLY, List.of(), "", "", null, null, Set.of(), false, 30, true, "SEED", NOW, null, 0);
        return SellingApplication.submit("a1", "SA-1", "org", "seller-user", "Seller", rule, "NEW", "SELLER", List.of(),
                null, NOW);
    }

    @Test
    void twoPeopleApproveAndTheApplicantNeverReviewsTheirOwn() {
        SellingApplication app = pending();
        assertThatThrownBy(() -> app.review(ReviewAction.APPROVE, "seller-user", "ALL_CHECKS_PASSED", null, null, null, NOW))
                .isInstanceOf(SellerForbidden.class);
        SellingApplication stepOne = app.review(ReviewAction.APPROVE, "reviewer", "ALL_CHECKS_PASSED", null, null, null, NOW);
        assertThat(stepOne.status()).isEqualTo(ApplicationStatus.AWAITING_FINAL_APPROVAL);
        assertThatThrownBy(() -> stepOne.decide(ReviewAction.FINAL_APPROVE, "reviewer", "ALL_CHECKS_PASSED", null, null,
                null, NOW)).isInstanceOf(SellerForbidden.class).hasMessageContaining("different person");
        SellingApplication done = stepOne.decide(ReviewAction.FINAL_APPROVE, "approver", "ALL_CHECKS_PASSED", null, null,
                null, NOW);
        assertThat(done.isApproved()).isTrue();
        assertThatThrownBy(() -> done.decide(ReviewAction.REJECT, "other", "OTHER", null, null, null, NOW))
                .isInstanceOf(SellerStateConflict.class);
    }

    @Test
    void disqualifyingNeedsAFutureReapplyDateAndIsRecordedAsNotAppealable() {
        SellingApplication stepOne = pending().review(ReviewAction.APPROVE, "reviewer", "X", null, null, null, NOW);
        assertThatThrownBy(() -> stepOne.decide(ReviewAction.DISQUALIFY, "approver", "NOT_ELIGIBLE", null, "No", null, NOW))
                .isInstanceOf(SellerRuleViolation.class);
        assertThatThrownBy(() -> stepOne.decide(ReviewAction.DISQUALIFY, "approver", "NOT_ELIGIBLE", null, "No",
                LocalDate.of(2026, 11, 11), NOW)).isInstanceOf(SellerRuleViolation.class);
        SellingApplication d = stepOne.decide(ReviewAction.DISQUALIFY, "approver", "NOT_ELIGIBLE", null, "No",
                LocalDate.of(2027, 1, 1), NOW);
        assertThat(d.status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(d.disqualified()).isTrue();
        assertThat(d.reapplyAfter()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(d.decisionNote()).isEqualTo("No");
    }

    @Test
    void infoRequestedGoesBackToTheSellerAndResubmittingRestartsAtStepOne() {
        SellingApplication info = pending().review(ReviewAction.REQUEST_INFO, "reviewer", "DOCUMENT_UNREADABLE", null,
                List.of("Permit scan"), "Cut off", NOW);
        assertThat(info.status()).isEqualTo(ApplicationStatus.INFO_REQUESTED);
        assertThat(info.decisionNote()).isEqualTo("Cut off");
        assertThatThrownBy(() -> pending().review(ReviewAction.REQUEST_INFO, "reviewer", "X", null, List.of(), null, NOW))
                .isInstanceOf(SellerRuleViolation.class);
        SellingApplication again = info.resubmit(List.of(), "Here", NOW);
        assertThat(again.status()).isEqualTo(ApplicationStatus.PENDING_REVIEW);
        assertThat(again.submissionCount()).isEqualTo(2);
        assertThat(again.reviewerId()).isNull();
        assertThatThrownBy(() -> pending().resubmit(List.of(), null, NOW)).isInstanceOf(SellerStateConflict.class);
    }

    @Test
    void anApprovalWarnsExpiresAndPausesOnTheDocumentExpiry() {
        SellingRule rule = new SellingRule(UUID.randomUUID(), SellingScope.category("supplements"), "Supplements",
                SellingLevel.APPLY, List.of(), "", "", null, null, Set.of(), false, 30, true, "SEED", NOW, null, 0);
        SellingApproval a = SellingApproval.applied("org", rule, "a1", NOW);
        SellingApplication app = pending();
        SellingApplication withDoc = new SellingApplication(app.id(), app.reference(), app.orgId(), app.applicantUserId(),
                app.applicantName(), app.scope(), app.scopeLabel(), app.ruleId(), "NEW", "SELLER",
                ApplicationStatus.APPROVED, List.of(new SellingDocument("d", SellingDocumentKind.PERMIT_LICENCE, "x", "f",
                null, LocalDate.of(2026, 12, 1), false, null)), null, null, null, null, null, List.of(), List.of(), null,
                null, false, null, 1, NOW, NOW, NOW, 0);
        SellingApproval approved = a.approved(withDoc, NOW);
        assertThat(approved.valid(NOW)).isTrue();
        assertThat(approved.daysToExpiry(NOW)).isEqualTo(20);
        assertThat(approved.graceEndsAt()).isEqualTo(Instant.parse("2026-12-15T23:59:59Z"));
        assertThat(approved.valid(Instant.parse("2026-12-02T00:00:00Z"))).isFalse();
        assertThat(approved.expired(Instant.parse("2026-12-02T00:00:00Z"))).isTrue();
        assertThat(approved.withPause(true, "late", NOW).valid(NOW)).isFalse();
    }
}
