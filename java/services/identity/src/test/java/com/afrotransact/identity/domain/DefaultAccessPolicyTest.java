package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultAccessPolicyTest {

    static final Role CUSTOMER = new Role(RoleKeys.CUSTOMER, Portal.ACCOUNT, "Customer", "", Set.of());
    static final Role SELLER_APPLICANT = new Role(RoleKeys.SELLER_APPLICANT, Portal.ACCOUNT, "Seller applicant", "",
            Set.of("applications:submit"));
    static final Role OPS = new Role("platform.operations_manager", Portal.PLATFORM, "Ops", "",
            Set.of("orders:read", "delivery:dispatch"));
    static final Role DRIVER = new Role(RoleKeys.DRIVER, Portal.PLATFORM, "Driver", "", Set.of("delivery:operate"));
    static final Role OWNER = new Role(RoleKeys.SELLER_OWNER, Portal.SELLER, "Owner", "",
            Set.of("catalog:write", "orders:read"));
    static final Map<String, Role> ROLES = Map.of(CUSTOMER.key(), CUSTOMER, SELLER_APPLICANT.key(), SELLER_APPLICANT,
            OPS.key(), OPS, DRIVER.key(), DRIVER, OWNER.key(), OWNER);

    private final AccessPolicy policy = new DefaultAccessPolicy();

    static UserAccount user(boolean enabled, String activeOrg) {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        return new UserAccount(UUID.randomUUID(), "Daniel", "Mensah", "daniel@example.com", true, "+17132490142",
                "{bcrypt}x", enabled, false, now, activeOrg, now, now, 0);
    }

    static AccessProfile profile(UserAccount u, Set<String> roles, Set<String> grants, List<OrgMembership> orgs) {
        return new AccessProfile(u, roles, grants, orgs, ROLES);
    }

    @Test
    void effectivePermissionsAreUnionOfRolesAndDirectGrants() {
        EffectiveAccess a = policy.resolve(profile(user(true, null), Set.of(OPS.key()), Set.of("delivery:operate"),
                List.of()));
        assertThat(a.permissions()).containsExactlyInAnyOrder("orders:read", "delivery:dispatch", "delivery:operate");
        assertThat(a.principalType()).isEqualTo(PrincipalType.STAFF);
        assertThat(a.staff()).isTrue();
    }

    @Test
    void driverOnlyIsDriverPrincipal() {
        EffectiveAccess a = policy.resolve(profile(user(true, null), Set.of(DRIVER.key()), Set.of(), List.of()));
        assertThat(a.principalType()).isEqualTo(PrincipalType.DRIVER);
        assertThat(a.staff()).isFalse();
        assertThat(a.can("delivery:operate")).isTrue();
    }

    @Test
    void orgRolePermissionsCountOnlyWhenApproved() {
        String org = "org_3k2j9x8q7w6e5r4t3y2u";
        OrgMembership pending = new OrgMembership(org, OrgKind.SELLER, "Mama Tee", OrgStatus.PENDING, OWNER.key());
        EffectiveAccess before = policy.resolve(profile(user(true, null),
                Set.of(CUSTOMER.key(), SELLER_APPLICANT.key()), Set.of(), List.of(pending)));
        assertThat(before.permissions()).containsExactly("applications:submit");
        assertThat(before.orgId()).contains(org);

        OrgMembership approved = new OrgMembership(org, OrgKind.SELLER, "Mama Tee", OrgStatus.APPROVED, OWNER.key());
        EffectiveAccess after = policy.resolve(profile(user(true, null), Set.of(CUSTOMER.key()), Set.of(),
                List.of(approved)));
        assertThat(after.permissions()).contains("catalog:write", "orders:read");
        assertThat(after.roles()).contains(RoleKeys.SELLER_OWNER, RoleKeys.CUSTOMER);
        assertThat(after.principalType()).isEqualTo(PrincipalType.SELLER_MEMBER);
    }

    @Test
    void activeOrgSelectsAmongSeveralMemberships() {
        String a = "org_alpha00000000000000";
        String b = "org_beta000000000000000";
        List<OrgMembership> orgs = List.of(
                new OrgMembership(a, OrgKind.SELLER, "Alpha", OrgStatus.APPROVED, OWNER.key()),
                new OrgMembership(b, OrgKind.SELLER, "Beta", OrgStatus.APPROVED, OWNER.key()));
        assertThat(policy.resolve(profile(user(true, null), Set.of(), Set.of(), orgs)).orgId()).contains(a);
        assertThat(policy.resolve(profile(user(true, b), Set.of(), Set.of(), orgs)).orgId()).contains(b);
    }

    @Test
    void disabledAccountsHaveNothing() {
        EffectiveAccess a = policy.resolve(profile(user(false, null), Set.of(OPS.key()), Set.of("delivery:operate"),
                List.of()));
        assertThat(a.permissions()).isEmpty();
        assertThat(a.roles()).isEmpty();
    }
}
