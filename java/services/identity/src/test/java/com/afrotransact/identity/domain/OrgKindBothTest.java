package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** S215: organisations that sell products and offer services, and content-aligned market codes. */
class OrgKindBothTest {

    private static final String ORG = "org_3k2j9x8q7w6e5r4t3y2u";

    private final TeamPolicy team = new DefaultTeamPolicy();
    private final AccessPolicy access = new DefaultAccessPolicy();
    private final LandingPolicy landing = new DefaultLandingPolicy(Set.of("afrotransact.com"));

    @Test
    void bothAcceptsSellerAndProviderRolesAndUsesSellerCenter() {
        Role sellerAdmin = new Role("seller.admin", Portal.SELLER, "Admin", "", Set.of());
        Role practitioner = new Role("provider.practitioner", Portal.PROVIDER, "Practitioner", "", Set.of());
        Role driver = new Role("platform.driver", Portal.PLATFORM, "Driver", "", Set.of());
        assertThatCode(() -> team.requireRoleFor(OrgKind.BOTH, sellerAdmin)).doesNotThrowAnyException();
        assertThatCode(() -> team.requireRoleFor(OrgKind.BOTH, practitioner)).doesNotThrowAnyException();
        assertThatThrownBy(() -> team.requireRoleFor(OrgKind.BOTH, driver))
                .isInstanceOf(IdentityRuleViolation.class);
        assertThat(team.portalOf(OrgKind.BOTH)).isEqualTo(Portal.SELLER);
        assertThat(team.teamModuleKey(OrgKind.BOTH)).isEqualTo("sTeam");
        assertThat(OrgKind.BOTH.ownerRole(OrgKind.PROVIDER)).isEqualTo(RoleKeys.PROVIDER_OWNER);
        assertThat(OrgKind.BOTH.ownerRole(null)).isEqualTo(RoleKeys.SELLER_OWNER);
        assertThat(OrgKind.SELLER.ownerRole(OrgKind.PROVIDER)).isEqualTo(RoleKeys.SELLER_OWNER);
        assertThat(OrgKind.BOTH.actsAs(OrgKind.PROVIDER)).isTrue();
        assertThat(OrgKind.SELLER.actsAs(OrgKind.PROVIDER)).isFalse();
    }

    @Test
    void membersOfBothOrganisationsActByTheirRoleAndLandInSellerCenter() {
        OrgMembership provider = new OrgMembership(ORG, OrgKind.BOTH, "Ama's", OrgStatus.APPROVED,
                RoleKeys.PROVIDER_OWNER);
        AccessProfile p = DefaultAccessPolicyTest.profile(DefaultAccessPolicyTest.user(true, null),
                Set.of(RoleKeys.CUSTOMER), Set.of(), List.of(provider));
        EffectiveAccess a = access.resolve(p);
        assertThat(a.principalType()).isEqualTo(PrincipalType.PROVIDER_MEMBER);
        assertThat(landing.landing(p, a)).isEqualTo(Landing.SELLER_CENTER);
        OrgMembership seller = new OrgMembership(ORG, OrgKind.BOTH, "Ama's", OrgStatus.APPROVED,
                RoleKeys.SELLER_OWNER);
        assertThat(access.resolve(DefaultAccessPolicyTest.profile(DefaultAccessPolicyTest.user(true, null),
                Set.of(), Set.of(), List.of(seller))).principalType()).isEqualTo(PrincipalType.SELLER_MEMBER);
    }

    @Test
    void marketCodesFollowContentsIsoCodes() {
        assertThat(MarketCodes.normalise(" us ")).isEqualTo("US");
        assertThat(MarketCodes.normalise("uk")).isEqualTo("GB");
        assertThat(MarketCodes.normalise("USA")).isEqualTo("US");
        assertThat(MarketCodes.normalise("Texas")).isNull();
        assertThat(MarketCodes.normalise("")).isNull();
        assertThat(MarketCodes.normalise(null)).isNull();
    }
}
