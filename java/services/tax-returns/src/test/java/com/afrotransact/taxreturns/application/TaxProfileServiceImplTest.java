package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.taxreturns.application.TaxProfileService.StatusChange;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;

class TaxProfileServiceImplTest {

    private final TestRules rules = new TestRules();
    private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);

    private TaxProfileService service(String... permissions) {
        when(rules.profileRepo.newRuleVersion(anyString(), anyString(), any())).thenReturn("tx-1+1");
        return new TaxProfileServiceImpl(rules.profileRepo, rules.catalog, new ClassificationEvents(publisher,
                mock(TaxTelemetry.class)), TestActors.staff(permissions), TestRules.CLOCK);
    }

    @Test
    void theListFiltersByStatusDepartmentAndText() {
        TaxProfileService service = service("tax:read");

        assertThat(service.list(null, null, null)).hasSize(73);
        assertThat(service.list(null, ProfileStatus.CONFLICT_OPEN, null)).extracting(v -> v.profile().categoryCode())
                .containsExactlyInAnyOrder("flavored-water-soda", "tutoring-consulting");
        assertThat(service.list(null, null, "rice")).extracting(v -> v.profile().categoryCode()).contains("rice-grains");
        assertThat(service.list("beverages", null, null)).allSatisfy(v ->
                assertThat(v.profile().departmentCode()).isEqualTo("beverages"));
    }

    @Test
    void liftingARefineCodeGateKeepsAnAuditAndStartsANewRuleVersion() {
        TaxProfileService service = service("tax:classify-admin", "tax:read");

        service.changeStatus("toys-games", new StatusChange(ProfileStatus.ACTIVE, "Owner set the provider code policy",
                null, null));

        verify(rules.profileRepo).updateStatus(eq("toys-games"), eq(ProfileStatus.ACTIVE), eq("STAFF:staff_1"), any());
        verify(rules.profileRepo).audit(any());
        verify(publisher).publish(eq("tax.classification.changed"), eq("taxreturns"), eq("toys-games"), any());
    }

    @Test
    void liftingAGateThatNeedsSignOffNeedsTheApprover() {
        TaxProfileService service = service("tax:classify-admin");

        assertThatThrownBy(() -> service.changeStatus("audiobooks", new StatusChange(ProfileStatus.ACTIVE,
                "The adviser signed off", null, null))).isInstanceOf(ReturnRuleViolation.class)
                .satisfies(ex -> assertThat(((ReturnRuleViolation) ex).code()).isEqualTo("tax.profile-approver-required"));
        verify(rules.profileRepo, never()).updateStatus(anyString(), any(), anyString(), any());

        service.changeStatus("audiobooks", new StatusChange(ProfileStatus.ACTIVE, "The adviser signed off",
                "Smith CPA", "OPINION-2026-11"));
        verify(rules.profileRepo).updateStatus(eq("audiobooks"), eq(ProfileStatus.ACTIVE), anyString(), any());
    }

    @Test
    void aConflictCannotBeLiftedWhileTheConflictingCodesAreStillInTheRules() {
        assertThatThrownBy(() -> service("tax:classify-admin").changeStatus("flavored-water-soda", new StatusChange(
                ProfileStatus.ACTIVE, "We just want it live", "Smith CPA", "X"))).isInstanceOf(ReturnRuleViolation.class)
                .satisfies(ex -> assertThat(((ReturnRuleViolation) ex).code()).isEqualTo("tax.profile-conflict-unresolved"));
    }

    @Test
    void closingAGateNeedsNoSignOffAndAReasonIsAlwaysRequired() {
        TaxProfileService service = service("tax:classify-admin");

        service.changeStatus("rice-grains", new StatusChange(ProfileStatus.NEEDS_APPROVAL, "Rule under review", null, null));
        verify(rules.profileRepo).updateStatus(eq("rice-grains"), eq(ProfileStatus.NEEDS_APPROVAL), anyString(), any());
        assertThatThrownBy(() -> service.changeStatus("rice-grains", new StatusChange(ProfileStatus.ACTIVE, "x", null, null)))
                .isInstanceOf(ReturnRuleViolation.class);
    }

    @Test
    void changingAStatusNeedsTheAdminPermissionReadingNeedsReadOnly() {
        assertThatThrownBy(() -> service("tax:read").changeStatus("toys-games", new StatusChange(ProfileStatus.ACTIVE,
                "Trying without the key", null, null))).isInstanceOf(ForbiddenActionException.class);
        assertThat(service("tax:read").get("toys-games").profile().status()).isEqualTo(ProfileStatus.REFINE_CODE);
        assertThatThrownBy(() -> service("tax:quote").list(null, null, null)).isInstanceOf(ForbiddenActionException.class);
    }
}
