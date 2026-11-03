package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.CatalogScope;
import com.afrotransact.seller.domain.ComplianceSection;
import com.afrotransact.seller.domain.Department;
import com.afrotransact.seller.domain.DepartmentRequest;
import com.afrotransact.seller.domain.DepartmentRequestStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.CatalogScopeRepository;
import com.afrotransact.seller.port.DepartmentDirectory;
import com.afrotransact.seller.port.OrganisationRepository;
import org.junit.jupiter.api.Test;

/** The departments an organisation may sell in: recorded at approval, requested by members, decided by staff. */
class CatalogScopeServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    private final CatalogScopeRepository scopes = mock(CatalogScopeRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final OrganisationRepository orgs = mock(OrganisationRepository.class);
    private final DepartmentDirectory directory = mock(DepartmentDirectory.class);
    private final AccessGuard access = mock(AccessGuard.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final CatalogScopeServiceImpl service = new CatalogScopeServiceImpl(scopes, applications, orgs, directory,
            access, new SellerSettings(Set.of("US"), List.of(), "org_retail"), Clock.fixed(NOW, ZoneOffset.UTC), events);

    private final Actor member = new Actor("user_1", PrincipalType.SELLER_MEMBER, Optional.of("org_1"), Set.of("seller.owner"),
            Set.of());
    private final Actor staff = new Actor("staff_1", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("*"));

    CatalogScopeServiceImplTest() {
        when(directory.departments()).thenReturn(List.of(
                new Department("groceries", "Groceries", Set.of(Department.Requirement.FOOD_PERMIT)),
                new Department("fashion", "Fashion", Set.of())));
        when(orgs.findById("org_1")).thenReturn(Optional.of(Organisation.draft("org_1", OrgKind.SELLER, "Ade", NOW)));
        when(access.current()).thenReturn(member);
        when(access.orgRole(eq("org_1"), anySet(), any())).thenReturn(member);
        when(access.staff(any())).thenReturn(staff);
    }

    private static ApplicationData chosen(String... departments) {
        return ApplicationData.empty().withCompliance(new ComplianceSection(List.of(departments), null, null,
                List.of(), null, true, true, "Ade"));
    }

    @Test
    void finalApprovalRecordsTheDepartmentsChosenAtOnboarding() {
        service.recordFromApplication("org_1", chosen("Fashion", "groceries", " "));
        verify(scopes).add("org_1", Set.of("fashion", "groceries"), "ONBOARDING", null, NOW);
    }

    @Test
    void retailAndApplicationsWithoutDepartmentsRecordNothing() {
        service.recordFromApplication("org_retail", chosen("fashion"));
        service.recordFromApplication("org_1", ApplicationData.empty());
        verify(scopes, never()).add(any(), anySet(), any(), any(), any());
    }

    @Test
    void retailIsExemptAndAnOrganisationWithNothingRecordedHasNoDepartments() {
        when(access.orgRoleOrStaff(any(), anySet(), any(), any(), any())).thenReturn(staff);
        when(scopes.departments("org_1")).thenReturn(Set.of());
        when(scopes.requestsOf("org_1")).thenReturn(List.of());
        CatalogScope own = service.forOrg("org_1");
        assertThat(own.exempt()).isFalse();
        assertThat(own.departments()).isEmpty();
        assertThat(service.forOrg("org_retail").exempt()).isTrue();
    }

    @Test
    void aMemberAsksForAnotherDepartmentAndSeesWhatItNeeds() {
        when(scopes.departments("org_1")).thenReturn(Set.of("fashion"));
        when(scopes.findPending("org_1", "groceries")).thenReturn(Optional.empty());
        when(scopes.insert(any())).thenAnswer(i -> i.getArgument(0));
        when(applications.findByOrg("org_1")).thenReturn(Optional.empty());
        DepartmentRequest r = service.request("org_1", "Groceries", "  I also sell spices ");
        assertThat(r.status()).isEqualTo(DepartmentRequestStatus.PENDING);
        assertThat(r.departmentCode()).isEqualTo("groceries");
        assertThat(r.note()).isEqualTo("I also sell spices");
        assertThat(r.requestedBy()).isEqualTo("user_1");
        assertThat(r.needs()).containsExactly(Department.Requirement.FOOD_PERMIT);
    }

    @Test
    void aDepartmentAlreadyAllowedOrUnknownIsRefusedInPlainWords() {
        when(scopes.departments("org_1")).thenReturn(Set.of("fashion"));
        assertThatThrownBy(() -> service.request("org_1", "fashion", null)).isInstanceOf(SellerStateConflict.class)
                .hasMessage("Your store can already sell in Fashion");
        assertThatThrownBy(() -> service.request("org_1", "hardware", null)).isInstanceOf(SellerRuleViolation.class)
                .hasMessage("Choose a department from the list");
    }

    @Test
    void approvingARequestAddsTheDepartmentAndRejectingDoesNot() {
        UUID id = UUID.randomUUID();
        DepartmentRequest pending = new DepartmentRequest(id, "org_1", "groceries", null,
                DepartmentRequestStatus.PENDING, "user_1", NOW, null, null, null, Set.of());
        when(scopes.findRequest(id)).thenReturn(Optional.of(pending));
        when(scopes.update(any())).thenAnswer(i -> i.getArgument(0));
        DepartmentRequest done = service.decide(id, true, "ok");
        assertThat(done.status()).isEqualTo(DepartmentRequestStatus.APPROVED);
        verify(scopes).add("org_1", Set.of("groceries"), "REQUEST", "staff_1", NOW);

        DepartmentRequest no = service.decide(id, false, "not now");
        assertThat(no.status()).isEqualTo(DepartmentRequestStatus.REJECTED);
        verify(scopes).add(any(), anySet(), any(), any(), any());
    }

    @Test
    void everyDecisionIsAuditedWithWhoWhatAndWhy() {
        UUID id = UUID.randomUUID();
        when(scopes.findRequest(id)).thenReturn(Optional.of(new DepartmentRequest(id, "org_1", "groceries", null,
                DepartmentRequestStatus.PENDING, "user_1", NOW, null, null, null, Set.of())));
        when(scopes.update(any())).thenAnswer(i -> i.getArgument(0));
        service.decide(id, false, "  Upload your food permit first ");
        verify(events).publish(eq(SellerEvents.DEPARTMENT_REQUEST_DECIDED), eq(SellerEvents.AGGREGATE_APPLICATION),
                eq("org_1"), eq(new DepartmentRequestDecidedPayload(id, "org_1", "groceries", "REJECTED", "staff_1",
                        "Upload your food permit first", NOW)));
    }

    @Test
    void aRejectionNeedsAReasonTheSellerCanRead() {
        UUID id = UUID.randomUUID();
        when(scopes.findRequest(id)).thenReturn(Optional.of(new DepartmentRequest(id, "org_1", "groceries", null,
                DepartmentRequestStatus.PENDING, "user_1", NOW, null, null, null, Set.of())));
        assertThatThrownBy(() -> service.decide(id, false, "  ")).isInstanceOf(SellerRuleViolation.class);
        assertThatThrownBy(() -> service.decide(id, false, null)).isInstanceOf(SellerRuleViolation.class);
        verify(scopes, never()).update(any());
        verify(events, never()).publish(any(), any(), any(), any());
    }

    @Test
    void staffChangeTheSetOnlyWithAReasonAndFromTheCatalogsDepartments() {
        assertThatThrownBy(() -> service.setDepartments("org_1", Set.of("fashion"), "short"))
                .isInstanceOf(SellerRuleViolation.class);
        assertThatThrownBy(() -> service.setDepartments("org_1", Set.of("hardware"), "Seller dropped hardware"))
                .isInstanceOf(SellerRuleViolation.class);
        when(scopes.departments("org_1")).thenReturn(Set.of("fashion"));
        when(scopes.requestsOf("org_1")).thenReturn(List.of());
        CatalogScope scope = service.setDepartments("org_1", Set.of("fashion"), "Confirmed by phone call");
        verify(scopes).replace("org_1", Set.of("fashion"), "STAFF", "staff_1", NOW);
        verify(events).publish(eq(SellerEvents.DEPARTMENTS_CHANGED), eq(SellerEvents.AGGREGATE_APPLICATION), eq("org_1"),
                eq(new DepartmentsChangedPayload("org_1", List.of("fashion"), List.of("fashion"), List.of(), List.of(),
                        "staff_1", "Confirmed by phone call", NOW)));
        assertThat(scope.departments()).containsExactly("fashion");
        assertThatThrownBy(() -> service.setDepartments("org_retail", Set.of(), "Retail sells everything"))
                .isInstanceOf(SellerStateConflict.class);
    }
}
