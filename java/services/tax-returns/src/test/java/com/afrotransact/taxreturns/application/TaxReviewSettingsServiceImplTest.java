package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.taxreturns.application.TaxReviewSettingsService.ReviewSettingsView;
import com.afrotransact.taxreturns.domain.profile.ReviewMode;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import org.junit.jupiter.api.Test;

/** The review settings: OFF (ALL) by default, a reason, the permission, the list and percentage checks, the history. */
class TaxReviewSettingsServiceImplTest {

    private final TestRules rules = new TestRules();
    private final InMemoryReviewSettings repo = new InMemoryReviewSettings();

    private TaxReviewSettingsService service(String... permissions) {
        return new TaxReviewSettingsServiceImpl(repo, rules.catalog, TestActors.staff(permissions), TestRules.CLOCK);
    }

    @Test
    void itIsOffByDefaultWithTheDefaultListAndFivePercent() {
        ReviewSettingsView v = service("tax:read").current();

        assertThat(v.settings().mode()).isEqualTo(ReviewMode.ALL);
        assertThat(v.settings().alwaysReview()).containsExactlyInAnyOrder("dietary-supplements", "otc-medicines",
                "medicated-skincare", "medical-devices", "baby-food-formula", "gift-cards");
        assertThat(v.settings().auditPercent()).isEqualTo(5);
        assertThat(v.alwaysReviewAlso()).containsExactly("NEEDS_APPROVAL", "REFINE_CODE", "CONFLICT_OPEN", "BLOCKED_BUNDLE");
        assertThat(v.history()).isEmpty();
        assertThat(v.changedBy()).isNull();
    }

    @Test
    void aChangeNeedsTheAdminPermissionAndAReasonAndIsRecordedInTheHistory() {
        assertThatThrownBy(() -> service("tax:read").change(ReviewMode.RISK_BASED, null, null, "Tax professional agreed"))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service("tax:configure").change(ReviewMode.RISK_BASED, null, null, "Tax professional agreed"))
                .as("configure alone is not enough for this switch").isInstanceOf(ForbiddenActionException.class);
        TaxReviewSettingsService admin = service("tax:classify-admin", "tax:read");
        assertThatThrownBy(() -> admin.change(ReviewMode.RISK_BASED, null, null, "short"))
                .isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("at least");
        assertThat(repo.history(5)).isEmpty();

        ReviewSettingsView v = admin.change(ReviewMode.RISK_BASED, List.of("haircare"), 10, "Tax professional agreed");

        assertThat(v.settings().mode()).isEqualTo(ReviewMode.RISK_BASED);
        assertThat(v.settings().alwaysReview()).containsExactly("haircare");
        assertThat(v.settings().auditPercent()).isEqualTo(10);
        assertThat(v.changedBy()).isEqualTo("staff_1");
        assertThat(v.history()).singleElement().satisfies(h -> {
            assertThat(h.from()).isNull();
            assertThat(h.to().mode()).isEqualTo(ReviewMode.RISK_BASED);
            assertThat(h.reason()).isEqualTo("Tax professional agreed");
        });
    }

    @Test
    void absentListAndPercentageKeepTheCurrentValuesAndAnUnchangedPutWritesNoHistory() {
        TaxReviewSettingsService admin = service("tax:classify-admin", "tax:read");
        admin.change(ReviewMode.RISK_BASED, List.of("haircare"), 7, "Tax professional agreed");

        ReviewSettingsView same = admin.change(ReviewMode.RISK_BASED, null, null, "Saving again, nothing new");

        assertThat(same.settings().alwaysReview()).containsExactly("haircare");
        assertThat(same.settings().auditPercent()).isEqualTo(7);
        assertThat(same.history()).hasSize(1);
        assertThat(admin.change(ReviewMode.ALL, null, null, "Back to reviewing everything").history()).hasSize(2);
    }

    @Test
    void unknownCategoriesAndAnImpossiblePercentageAreRefusedWithFieldMessages() {
        TaxReviewSettingsService admin = service("tax:classify-admin", "tax:read");

        assertThatThrownBy(() -> admin.change(ReviewMode.RISK_BASED, List.of("haircare", "not-a-category"), null,
                "Tax professional agreed")).isInstanceOf(ReturnRuleViolation.class)
                .hasMessageContaining("not-a-category");
        assertThatThrownBy(() -> admin.change(ReviewMode.RISK_BASED, null, 101, "Tax professional agreed"))
                .isInstanceOf(ReturnRuleViolation.class);
        assertThatThrownBy(() -> admin.change(ReviewMode.RISK_BASED, null, -1, "Tax professional agreed"))
                .isInstanceOf(ReturnRuleViolation.class);
        assertThat(repo.current().mode()).isEqualTo(ReviewMode.ALL);
    }
}
