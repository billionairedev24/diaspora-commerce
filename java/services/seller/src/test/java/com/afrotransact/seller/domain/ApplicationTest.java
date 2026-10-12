package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    private static final Instant LATER = Instant.parse("2026-10-12T14:03:00Z");

    private static Application approvedBy(String approver) {
        return draft().submit(NOW).review(ReviewAction.APPROVE, "reviewer", "ALL_CHECKS_PASSED", null, null, NOW)
                .decide(ReviewAction.FINAL_APPROVE, approver, "ALL_CHECKS_PASSED", LATER);
    }

    private static TimelineEntry entry(ReviewAction action, ApplicationStatus from, ApplicationStatus to,
                                       String actor) {
        return new TimelineEntry(UUID.randomUUID(), "app_1", action, from, to, actor, "ALL_CHECKS_PASSED", null,
                null, LATER);
    }

    @Test
    void anAlreadyApprovedApplicationSaysByWhomAndWhenNeverThatItNeedsAReviewerDecision() {
        Application approved = approvedBy("approver");
        assertThatThrownBy(() -> approved.decide(ReviewAction.FINAL_APPROVE, "someone-else", "ALL_CHECKS_PASSED",
                LATER)).isInstanceOfSatisfying(ApplicationAlreadyDecided.class, e -> {
                    assertThat(e.code()).isEqualTo("seller.already-decided");
                    assertThat(e.status()).isEqualTo(ApplicationStatus.APPROVED);
                    assertThat(e.deciderId()).isEqualTo("approver");
                    assertThat(e.decidedAt()).isEqualTo(LATER);
                    assertThat(e.getMessage()).isEqualTo(
                            "This application is already approved (by approver, 12 Oct 2026, 14:03 UTC)")
                            .doesNotContain("reviewer decision");
                    assertThat(e.named("Ada Obi", "someone-else").getMessage())
                            .isEqualTo("This application is already approved (by Ada Obi, 12 Oct 2026, 14:03 UTC)");
                });
        assertThatThrownBy(() -> approved.decide(ReviewAction.REJECT, "approver", "OTHER", LATER))
                .isInstanceOfSatisfying(ApplicationAlreadyDecided.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(
                            "This application is already approved (by you, 12 Oct 2026, 14:03 UTC)");
                    assertThat(e.named("Ada Obi", "approver").deciderLabel()).as("you stays you").isEqualTo("you");
                });
        assertThatThrownBy(() -> approved.decide(ReviewAction.SEND_BACK, "x", "OTHER", LATER))
                .isInstanceOf(ApplicationAlreadyDecided.class);
        assertThatThrownBy(() -> approved.review(ReviewAction.APPROVE, "x", "OTHER", null, null, LATER))
                .as("step 1 on an approved application").isInstanceOfSatisfying(ApplicationAlreadyDecided.class,
                        e -> assertThat(e.code()).isEqualTo("seller.already-decided"));
        Application rejected = draft().submit(NOW).review(ReviewAction.APPROVE, "reviewer", "OTHER", null, null, NOW)
                .decide(ReviewAction.REJECT, "approver", "OTHER", LATER);
        assertThatThrownBy(() -> rejected.decide(ReviewAction.FINAL_APPROVE, "x", "OTHER", LATER))
                .hasMessageStartingWith("This application is already rejected (by approver");
    }

    @Test
    void stepOneAlreadyDecidedAndOtherStatesSayWhatIsActuallyGoingOn() {
        Application awaiting = draft().submit(NOW).review(ReviewAction.APPROVE, "reviewer", "OTHER", null, null,
                LATER);
        assertThatThrownBy(() -> awaiting.review(ReviewAction.APPROVE, "other", "OTHER", null, null, LATER))
                .isInstanceOfSatisfying(ApplicationAlreadyDecided.class, e -> {
                    assertThat(e.code()).isEqualTo("seller.already-reviewed");
                    assertThat(e.getMessage()).isEqualTo("This application is already approved for final review (by "
                            + "reviewer, 12 Oct 2026, 14:03 UTC); it is waiting for final approval");
                });
        Application flagged = draft().submit(NOW).review(ReviewAction.FLAG, "reviewer", "OTHER", List.of("X"), null,
                LATER);
        assertThatThrownBy(() -> flagged.review(ReviewAction.REQUEST_INFO, "reviewer", "OTHER", null,
                List.of("Y"), LATER)).hasMessageStartingWith("This application is already flagged (by you");
        Application submitted = draft().submit(NOW);
        assertThatThrownBy(() -> submitted.decide(ReviewAction.FINAL_APPROVE, "a", "OTHER", NOW))
                .hasMessage("Application APP-30200 needs a reviewer decision (step 1) first");
        assertThatThrownBy(() -> draft().decide(ReviewAction.FINAL_APPROVE, "a", "OTHER", NOW))
                .hasMessage("Application APP-30200 hasn't been submitted yet");
        Application info = submitted.review(ReviewAction.REQUEST_INFO, "reviewer", "OTHER", null, List.of("Z"), NOW);
        assertThatThrownBy(() -> info.decide(ReviewAction.REJECT, "a", "OTHER", NOW))
                .hasMessage("Application APP-30200 is waiting for the applicant to send the information requested");
        assertThatThrownBy(() -> info.review(ReviewAction.APPROVE, "r2", "OTHER", null, null, NOW))
                .hasMessage("Application APP-30200 is waiting for the applicant to send the information requested");
    }

    @Test
    void theSamePersonRepeatingTheDecisionThatPutTheApplicationInItsStateIsARepeat() {
        Application approved = approvedBy("approver");
        TimelineEntry finalApproval = entry(ReviewAction.FINAL_APPROVE, ApplicationStatus.AWAITING_FINAL_APPROVAL,
                ApplicationStatus.APPROVED, "approver");
        assertThat(approved.isRepeatOf(ReviewAction.FINAL_APPROVE, "approver", finalApproval)).isTrue();
        assertThat(approved.isRepeatOf(ReviewAction.FINAL_APPROVE, "someone-else", finalApproval))
                .as("another approver is told it is already approved").isFalse();
        assertThat(approved.isRepeatOf(ReviewAction.REJECT, "approver", finalApproval))
                .as("a different decision is not a repeat").isFalse();
        assertThat(approved.isRepeatOf(ReviewAction.FINAL_APPROVE, "approver", null)).isFalse();

        Application awaiting = draft().submit(NOW).review(ReviewAction.APPROVE, "reviewer", "OTHER", null, null, NOW);
        assertThat(awaiting.isRepeatOf(ReviewAction.APPROVE, "reviewer", entry(ReviewAction.APPROVE,
                ApplicationStatus.PENDING_REVIEW, ApplicationStatus.AWAITING_FINAL_APPROVAL, "reviewer"))).isTrue();
        Application flagged = draft().submit(NOW).review(ReviewAction.FLAG, "reviewer", "OTHER", List.of("X"), null,
                NOW);
        assertThat(flagged.isRepeatOf(ReviewAction.FLAG, "reviewer", entry(ReviewAction.FLAG,
                ApplicationStatus.PENDING_REVIEW, ApplicationStatus.FLAGGED, "reviewer"))).isTrue();
        Application info = draft().submit(NOW).review(ReviewAction.REQUEST_INFO, "reviewer", "OTHER", null,
                List.of("Y"), NOW);
        assertThat(info.isRepeatOf(ReviewAction.REQUEST_INFO, "reviewer", entry(ReviewAction.REQUEST_INFO,
                ApplicationStatus.PENDING_REVIEW, ApplicationStatus.INFO_REQUESTED, "reviewer"))).isTrue();
        Application back = flagged.decide(ReviewAction.SEND_BACK, "approver", "OTHER", NOW);
        assertThat(back.isRepeatOf(ReviewAction.SEND_BACK, "approver", entry(ReviewAction.SEND_BACK,
                ApplicationStatus.FLAGGED, ApplicationStatus.PENDING_REVIEW, "approver"))).isTrue();
        Application rejected = awaiting.decide(ReviewAction.REJECT, "approver", "OTHER", NOW);
        assertThat(rejected.isRepeatOf(ReviewAction.REJECT, "approver", entry(ReviewAction.REJECT,
                ApplicationStatus.AWAITING_FINAL_APPROVAL, ApplicationStatus.REJECTED, "approver"))).isTrue();
        assertThat(back.isRepeatOf(ReviewAction.SEND_BACK, "approver", entry(ReviewAction.SUBMITTED,
                ApplicationStatus.DRAFT, ApplicationStatus.PENDING_REVIEW, "approver")))
                .as("only the latest entry counts").isFalse();
    }
}
