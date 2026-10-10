package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultTeamPolicyTest {

    private final TeamPolicy policy = new DefaultTeamPolicy();

    @Test
    void rolesMustFitTheOrganisationKind() {
        Role sellerAdmin = new Role("seller.admin", Portal.SELLER, "Admin", "", Set.of());
        Role practitioner = new Role("provider.practitioner", Portal.PROVIDER, "Practitioner", "", Set.of());
        Role driver = new Role("platform.driver", Portal.PLATFORM, "Driver", "", Set.of());
        assertThatCode(() -> policy.requireRoleFor(OrgKind.SELLER, sellerAdmin)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireRoleFor(OrgKind.PROVIDER, practitioner)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireRoleFor(OrgKind.SELLER, practitioner))
                .isInstanceOf(IdentityRuleViolation.class)
                .satisfies(e -> assertThat(((IdentityRuleViolation) e).code()).isEqualTo("identity.wrong-portal"))
                .satisfies(e -> assertThat(((IdentityRuleViolation) e).fieldErrors()).containsKey("roleKey"));
        assertThatThrownBy(() -> policy.requireRoleFor(OrgKind.PROVIDER, sellerAdmin))
                .isInstanceOf(IdentityRuleViolation.class);
        assertThatThrownBy(() -> policy.requireRoleFor(OrgKind.SELLER, driver)).isInstanceOf(IdentityRuleViolation.class);
        assertThat(policy.teamModuleKey(OrgKind.SELLER)).isEqualTo("sTeam");
        assertThat(policy.teamModuleKey(OrgKind.PROVIDER)).isEqualTo("pTeam");
        assertThat(policy.portalOf(OrgKind.PROVIDER)).isEqualTo(Portal.PROVIDER);
    }

    @Test
    void theLastOwnerCannotBeDemotedOrRemoved() {
        assertThatThrownBy(() -> policy.requireOwnerRemains("seller.owner", "seller.admin", 0))
                .isInstanceOf(IdentityRuleViolation.class)
                .satisfies(e -> assertThat(((IdentityRuleViolation) e).code()).isEqualTo("identity.last-owner"));
        assertThatThrownBy(() -> policy.requireOwnerRemains("provider.owner", null, 0))
                .isInstanceOf(IdentityRuleViolation.class);
        assertThatCode(() -> policy.requireOwnerRemains("seller.owner", "seller.admin", 1)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireOwnerRemains("seller.owner", null, 2)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireOwnerRemains("seller.owner", "seller.owner", 0)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireOwnerRemains("seller.admin", null, 0)).doesNotThrowAnyException();
        assertThat(policy.isOwnerRole("seller.owner")).isTrue();
        assertThat(policy.isOwnerRole("provider.owner")).isTrue();
        assertThat(policy.isOwnerRole("seller.admin")).isFalse();
        assertThat(policy.isOwnerRole(null)).isFalse();
    }

    @Test
    void tokensAreRandomAndOnlyTheirHashIsKept() {
        String a = policy.newToken();
        String b = policy.newToken();
        assertThat(a).isNotEqualTo(b).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(policy.hashToken(a)).hasSize(64).isEqualTo(SecretHashing.sha256Hex(a))
                .isEqualTo(policy.hashToken(" " + a + " ")).isNotEqualTo(policy.hashToken(b));
    }

    @Test
    void invitationsExpireAfterSevenDaysAndKeepTheirTerminalStatus() {
        Instant created = Instant.parse("2026-10-01T09:00:00Z");
        OrgInvitation inv = new OrgInvitation(UUID.randomUUID(), "org_x", " Bola@Example.COM ", "seller.catalog",
                "h", InvitationStatus.PENDING, "owner", created, created.plus(TeamPolicy.INVITATION_TTL), null, null,
                null, null);
        assertThat(inv.email()).isEqualTo("bola@example.com");
        assertThat(inv.pendingAt(created.plusSeconds(60))).isTrue();
        assertThat(inv.statusAt(Instant.parse("2026-10-08T08:59:59Z"))).isEqualTo(InvitationStatus.PENDING);
        assertThat(inv.statusAt(Instant.parse("2026-10-08T09:00:00Z"))).isEqualTo(InvitationStatus.EXPIRED);
        UUID user = UUID.randomUUID();
        OrgInvitation accepted = inv.accepted(user, created.plusSeconds(5));
        assertThat(accepted.statusAt(Instant.parse("2026-12-01T00:00:00Z"))).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(accepted.acceptedBy()).isEqualTo(user);
        assertThat(inv.revoked("admin", created).statusAt(created)).isEqualTo(InvitationStatus.REVOKED);
    }
}
