package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultLandingPolicyTest {

    private final AccessPolicy access = new DefaultAccessPolicy();
    private final LandingPolicy landing = new DefaultLandingPolicy(Set.of("afrotransact.com"));

    private Landing landingFor(Set<String> roles, Set<String> grants, List<OrgMembership> orgs) {
        AccessProfile p = DefaultAccessPolicyTest.profile(DefaultAccessPolicyTest.user(true, null), roles, grants,
                orgs);
        return landing.landing(p, access.resolve(p));
    }

    @Test
    void customersStayInTheShop() {
        assertThat(landingFor(Set.of(RoleKeys.CUSTOMER), Set.of(), List.of())).isEqualTo(Landing.SHOP);
    }

    @Test
    void applicantsGoToOnboardingAndApprovedSellersToSellerCenter() {
        assertThat(landingFor(Set.of(RoleKeys.CUSTOMER, RoleKeys.SELLER_APPLICANT), Set.of(), List.of()))
                .isEqualTo(Landing.SELLER_ONBOARDING);
        assertThat(landingFor(Set.of(RoleKeys.CUSTOMER, RoleKeys.PROVIDER_APPLICANT), Set.of(), List.of()))
                .isEqualTo(Landing.PROVIDER_ONBOARDING);
        UUID org = UUID.randomUUID();
        assertThat(landingFor(Set.of(RoleKeys.CUSTOMER), Set.of(), List.of(
                new OrgMembership(org, OrgKind.SELLER, "Ade's Kitchen", OrgStatus.PENDING, RoleKeys.SELLER_OWNER))))
                .isEqualTo(Landing.SELLER_ONBOARDING);
        assertThat(landingFor(Set.of(RoleKeys.CUSTOMER, RoleKeys.SELLER_APPLICANT), Set.of(), List.of(
                new OrgMembership(org, OrgKind.SELLER, "Ade's Kitchen", OrgStatus.APPROVED, RoleKeys.SELLER_OWNER))))
                .isEqualTo(Landing.SELLER_CENTER);
        assertThat(landingFor(Set.of(RoleKeys.CUSTOMER), Set.of(), List.of(
                new OrgMembership(org, OrgKind.PROVIDER, "Ama's Braiding", OrgStatus.APPROVED,
                        RoleKeys.PROVIDER_OWNER))))
                .isEqualTo(Landing.PROVIDER_HUB);
    }

    @Test
    void staffLandInConsoleAndDriversInDriverApp() {
        assertThat(landingFor(Set.of("platform.operations_manager"), Set.of("delivery:operate"), List.of()))
                .isEqualTo(Landing.STAFF_CONSOLE);
        assertThat(landingFor(Set.of(RoleKeys.DRIVER), Set.of(), List.of())).isEqualTo(Landing.DRIVER_APP);
    }

    @Test
    void recognisesStaffEmailDomains() {
        assertThat(landing.isStaffEmail("Tunde@AfroTransact.com")).isTrue();
        assertThat(landing.isStaffEmail("tunde@notafrotransact.com")).isFalse();
        assertThat(landing.isStaffEmail("tunde@afrotransact.com.evil.io")).isFalse();
    }
}
