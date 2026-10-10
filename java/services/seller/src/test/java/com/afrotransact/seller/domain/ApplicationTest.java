package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ApplicationTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private static Application draft() {
        return Application.start("app_1", "APP-30200", "org_1", "applicant", OrgKind.SELLER, ApplicationData.empty(),
                Set.of(), NOW);
    }

    @Test
    void reviewerApprovesThenADifferentPersonGivesFinalApproval() {
        Application submitted = draft().submit(NOW);
        assertThat(submitted.status()).isEqualTo(ApplicationStatus.PENDING_REVIEW);
        assertThat(submitted.submissionCount()).isEqualTo(1);
        Application reviewed = submitted.review(ReviewAction.APPROVE, "reviewer", "ALL_CHECKS_PASSED", null, null,
                NOW);
        assertThat(reviewed.status()).isEqualTo(ApplicationStatus.AWAITING_FINAL_APPROVAL);
        assertThat(reviewed.reviewerId()).isEqualTo("reviewer");

        assertThatThrownBy(() -> reviewed.decide(ReviewAction.FINAL_APPROVE, "reviewer", "ALL_CHECKS_PASSED", NOW))
                .isInstanceOfSatisfying(SellerForbidden.class,
                        e -> assertThat(e.code()).isEqualTo("seller.same-person-approval"));

        Application approved = reviewed.decide(ReviewAction.FINAL_APPROVE, "approver", "ALL_CHECKS_PASSED", NOW);
        assertThat(approved.status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(approved.finalApproverId()).isEqualTo("approver");
        assertThat(approved.isApproved()).isTrue();
    }

    @Test
    void flaggedApplicationsListFailedChecksAndGoToTheFinalApprover() {
        Application submitted = draft().submit(NOW);
        assertThatThrownBy(() -> submitted.review(ReviewAction.FLAG, "reviewer", "PERMIT_MISSING", List.of(), null,
                NOW)).isInstanceOf(SellerRuleViolation.class);
        Application flagged = submitted.review(ReviewAction.FLAG, "reviewer", "PERMIT_MISSING",
                List.of("FMC OTI licence", "Reputation scan"), null, NOW);
        assertThat(flagged.status()).isEqualTo(ApplicationStatus.FLAGGED);
        assertThat(flagged.failedChecks()).containsExactly("FMC OTI licence", "Reputation scan");
        assertThatThrownBy(() -> flagged.decide(ReviewAction.REJECT, "reviewer", "PERMIT_MISSING", NOW))
                .isInstanceOf(SellerForbidden.class);
        assertThat(flagged.decide(ReviewAction.REJECT, "approver", "PERMIT_MISSING", NOW).status())
                .isEqualTo(ApplicationStatus.REJECTED);
    }

    @Test
    void sendBackReturnsToPendingReviewAndClearsTheReviewer() {
        Application flagged = draft().submit(NOW).review(ReviewAction.FLAG, "reviewer", "OTHER", List.of("X"), null,
                NOW);
        Application back = flagged.decide(ReviewAction.SEND_BACK, "approver", "OTHER", NOW);
        assertThat(back.status()).isEqualTo(ApplicationStatus.PENDING_REVIEW);
        assertThat(back.reviewerId()).isNull();
        assertThat(back.failedChecks()).isEmpty();
        assertThat(back.review(ReviewAction.APPROVE, "reviewer", "OTHER", null, null, NOW).status())
                .isEqualTo(ApplicationStatus.AWAITING_FINAL_APPROVAL);
    }

    @Test
    void requestInfoLetsTheApplicantEditAndResubmit() {
        Application info = draft().submit(NOW).review(ReviewAction.REQUEST_INFO, "reviewer", "DOCUMENT_UNREADABLE",
                null, List.of("Individual cosmetology licence"), NOW);
        assertThat(info.status()).isEqualTo(ApplicationStatus.INFO_REQUESTED);
        assertThat(info.requestedItems()).containsExactly("Individual cosmetology licence");
        Application edited = info.edit(ApplicationData.empty(), OnboardingStep.CATALOG_COMPLIANCE, Set.of(), NOW);
        Application resubmitted = edited.submit(NOW);
        assertThat(resubmitted.status()).isEqualTo(ApplicationStatus.PENDING_REVIEW);
        assertThat(resubmitted.submissionCount()).isEqualTo(2);
        assertThat(resubmitted.requestedItems()).isEmpty();
    }

    @Test
    void lockedOutsideDraftAndInfoRequested() {
        Application submitted = draft().submit(NOW);
        assertThatThrownBy(() -> submitted.edit(ApplicationData.empty(), OnboardingStep.BUSINESS, Set.of(), NOW))
                .isInstanceOf(SellerStateConflict.class);
        assertThatThrownBy(() -> draft().review(ReviewAction.APPROVE, "r", "OTHER", null, null, NOW))
                .isInstanceOfSatisfying(SellerStateConflict.class,
                        e -> assertThat(e.code()).isEqualTo("seller.not-pending-review"));
        assertThatThrownBy(() -> submitted.decide(ReviewAction.FINAL_APPROVE, "a", "OTHER", NOW))
                .isInstanceOfSatisfying(SellerStateConflict.class,
                        e -> assertThat(e.code()).isEqualTo("seller.not-awaiting-final-approval"));
        assertThatThrownBy(() -> submitted.review(ReviewAction.FINAL_APPROVE, "r", "OTHER", null, null, NOW))
                .isInstanceOf(SellerRuleViolation.class);
    }

    @Test
    void nobodyReviewsTheirOwnApplication() {
        Application submitted = draft().submit(NOW);
        assertThatThrownBy(() -> submitted.review(ReviewAction.APPROVE, "applicant", "OTHER", null, null, NOW))
                .isInstanceOfSatisfying(SellerForbidden.class,
                        e -> assertThat(e.code()).isEqualTo("seller.self-review"));
    }
}
