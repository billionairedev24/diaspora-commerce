package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AssignmentRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-12T14:00:00Z");

    private final Application submitted = Application.start("a1", "APP-1", "org1", "applicant", OrgKind.SELLER,
            ApplicationData.empty(), Set.of(), NOW).submit(NOW);
    private final Application reviewed = submitted.review(ReviewAction.APPROVE, "rita", "OK", null, null, NOW);

    @Test
    void reviewerNeedsThePermissionAndMayNotBeTheApplicant() {
        assertThat(AssignmentRules.eligible(submitted, ReviewStage.REVIEW, "rita", true)).isTrue();
        assertThat(AssignmentRules.eligible(submitted, ReviewStage.REVIEW, "rita", false)).isFalse();
        assertThat(AssignmentRules.eligible(submitted, ReviewStage.REVIEW, "applicant", true)).isFalse();
    }

    @Test
    void theFirstReviewerNeverHoldsTheApprovalEvenWithBothRoles() {
        assertThat(AssignmentRules.eligible(reviewed, ReviewStage.APPROVAL, "rita", true)).isFalse();
        assertThat(AssignmentRules.eligible(reviewed, ReviewStage.APPROVAL, "adam", true)).isTrue();
        assertThat(AssignmentRules.reason(reviewed, ReviewStage.APPROVAL, "rita", true)).contains("different person");
    }

    @Test
    void aPersonWithBothRolesMayReviewOneApplicationAndApproveAnother() {
        Application other = Application.start("a2", "APP-2", "org2", "someone", OrgKind.SELLER,
                ApplicationData.empty(), Set.of(), NOW).submit(NOW);
        assertThat(AssignmentRules.eligible(other, ReviewStage.REVIEW, "both", true)).isTrue();
        Application otherReviewed = other.review(ReviewAction.APPROVE, "rita", "OK", null, null, NOW);
        assertThat(AssignmentRules.eligible(otherReviewed, ReviewStage.APPROVAL, "both", true)).isTrue();
    }

    @Test
    void requireNamesThePersonAndTheReason() {
        assertThatThrownBy(() -> AssignmentRules.require(reviewed, ReviewStage.APPROVAL, "rita", true, "Rita Okoro"))
                .isInstanceOf(SellerRuleViolation.class).hasMessageContaining("Rita Okoro")
                .hasMessageContaining("first review");
    }

    @Test
    void stageFollowsTheStatus() {
        assertThat(ReviewStage.of(ApplicationStatus.PENDING_REVIEW)).contains(ReviewStage.REVIEW);
        assertThat(ReviewStage.of(ApplicationStatus.FLAGGED)).contains(ReviewStage.APPROVAL);
        assertThat(ReviewStage.of(ApplicationStatus.AWAITING_FINAL_APPROVAL)).contains(ReviewStage.APPROVAL);
        assertThat(ReviewStage.of(ApplicationStatus.INFO_REQUESTED)).isEmpty();
        assertThat(ReviewStage.of(ApplicationStatus.APPROVED)).isEmpty();
    }

    @Test
    void slaStates() {
        Duration day = Duration.ofHours(24);
        assertThat(SlaState.of(NOW, NOW.plus(Duration.ofHours(17)), day, 75)).isEqualTo(SlaState.ON_TIME);
        assertThat(SlaState.of(NOW, NOW.plus(Duration.ofHours(18)), day, 75)).isEqualTo(SlaState.DUE_SOON);
        assertThat(SlaState.of(NOW, NOW.plus(Duration.ofHours(24)), day, 75)).isEqualTo(SlaState.OVERDUE);
    }

    @Test
    void settingsValidation() {
        ReviewSettings ok = ReviewSettings.defaults();
        assertThat(ok.problems()).isEmpty();
        ReviewSettings bad = new ReviewSettings(0, 99999, 100, 4, 24, AutoAssignMode.NONE, false, false,
                new EscalationChain(null, new EscalationTarget("role", null)), null, null, null, null, null);
        assertThat(bad.problems()).containsKeys("reviewSlaHours", "approvalSlaHours", "dueSoonPercent", "reviewChain");
    }

    @Test
    void caseTransitionsClearTheAssigneeAndEscalationButKeepTheReturnCount() {
        ApplicationCase c = ApplicationCase.idle("a1", NOW).enter(ReviewStage.APPROVAL, NOW)
                .assign("adam", "Adam Ade", "sup", "Sue", NOW.plusSeconds(60))
                .escalate(1, "late", List.of("sup"), List.of("Sue"), NOW.plusSeconds(120))
                .returned("adam", "Adam Ade", "check the TIN", NOW.plusSeconds(180));
        assertThat(c.returnCount()).isEqualTo(1);
        ApplicationCase next = c.enter(ReviewStage.REVIEW, NOW.plusSeconds(200));
        assertThat(next.assigned()).isFalse();
        assertThat(next.escalationLevel()).isZero();
        assertThat(next.stageEnteredAt()).isEqualTo(NOW.plusSeconds(200));
        assertThat(next.returnCount()).isEqualTo(1);
        assertThat(next.returnedByName()).isEqualTo("Adam Ade");
        assertThat(next.withoutReturn(NOW).returnedByName()).isNull();
        assertThat(c.escalate(1, "again", List.of(), List.of(), NOW)).isSameAs(c);
    }
}
