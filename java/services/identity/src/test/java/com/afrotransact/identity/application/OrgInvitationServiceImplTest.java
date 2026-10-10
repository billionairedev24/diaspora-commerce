package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.InvitationAccepted;
import com.afrotransact.identity.application.IdentityEventPayloads.InvitationCreated;
import com.afrotransact.identity.application.IdentityEventPayloads.InvitationRevoked;
import com.afrotransact.identity.domain.DefaultTeamPolicy;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.InvitationStatus;
import com.afrotransact.identity.domain.OrgInvitation;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.ModuleAccessRepository;
import com.afrotransact.identity.port.OrgInvitationRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrgInvitationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String ORG = "org_invite00000000000001";

    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final ModuleAccessRepository modules = mock(ModuleAccessRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final OrgInvitationRepository invitations = mock(OrgInvitationRepository.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final DefaultTeamPolicy policy = new DefaultTeamPolicy();
    private final UUID owner = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private Actor caller;

    private OrgInvitationServiceImpl service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CurrentUser current = new CurrentUser(() -> caller);
        OrgTeamAccess access = new OrgTeamAccess(current, orgs, roles, modules, policy);
        MembershipWriter writer = new MembershipWriter(orgs, users, changeLog, events, clock);
        InvitationRedeemer redeemer = new InvitationRedeemer(invitations, orgs, writer, policy, changeLog, events,
                clock);
        return new OrgInvitationServiceImpl(access, invitations, orgs, roles, users, redeemer, current, policy,
                new InvitationSettings("https://seller.example.com/invitations/accept"), changeLog, events, clock);
    }

    @BeforeEach
    void org() {
        when(orgs.findById(ORG)).thenReturn(Optional.of(new Organization(ORG, OrgKind.SELLER, "Mama Put",
                OrgStatus.APPROVED, "US", NOW)));
        when(orgs.memberRole(ORG, owner)).thenReturn(Optional.of("seller.owner"));
        when(orgs.memberRole(ORG, admin)).thenReturn(Optional.of("seller.admin"));
        when(users.findById(owner)).thenReturn(Optional.of(account(owner, "ada@example.com")));
        when(roles.role("seller.owner")).thenReturn(Optional.of(new Role("seller.owner", Portal.SELLER, "Owner", "",
                Set.of("team:manage"))));
        when(roles.role("seller.admin")).thenReturn(Optional.of(new Role("seller.admin", Portal.SELLER, "Admin", "",
                Set.of("team:manage"))));
        when(roles.role("seller.catalog")).thenReturn(Optional.of(new Role("seller.catalog", Portal.SELLER,
                "Catalog manager", "", Set.of())));
        when(roles.role("provider.finance")).thenReturn(Optional.of(new Role("provider.finance", Portal.PROVIDER,
                "Finance", "", Set.of())));
        when(orgs.setMember(eq(ORG), any(), anyString())).thenReturn(true);
        when(users.update(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
        caller = user(owner);
    }

    @Test
    void inviteStoresOnlyTheTokenHashAndPublishesTheTokenWithASevenDayExpiry() {
        InvitationView v = service().invite(ORG, " Bola@Example.com ", "seller.catalog");
        assertThat(v.email()).isEqualTo("bola@example.com");
        assertThat(v.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(v.roleName()).isEqualTo("Catalog manager");
        assertThat(v.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));

        ArgumentCaptor<OrgInvitation> stored = ArgumentCaptor.forClass(OrgInvitation.class);
        verify(invitations).insert(stored.capture());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("identity.invitation.created"), eq("identity"), eq(ORG), payload.capture());
        InvitationCreated e = (InvitationCreated) payload.getValue();
        assertThat(e.token()).hasSize(43);
        assertThat(stored.getValue().tokenHash()).isEqualTo(SecretHashing.sha256Hex(e.token()))
                .isNotEqualTo(e.token());
        assertThat(e.acceptUrl()).isEqualTo("https://seller.example.com/invitations/accept?token=" + e.token());
        assertThat(e.recipient().email()).isEqualTo("bola@example.com");
        assertThat(e.orgName()).isEqualTo("Mama Put");
        assertThat(e.inviterName()).isEqualTo("Ada Okafor");
        assertThat(e.expiresInDays()).isEqualTo(7);
        assertThat(e.transactional()).isTrue();
    }

    @Test
    void invitingAgainReplacesTheOpenInvitation() {
        OrgInvitation old = invitation("bola@example.com", "seller.catalog", InvitationStatus.PENDING);
        when(invitations.findPending(ORG, "bola@example.com")).thenReturn(Optional.of(old));
        service().invite(ORG, "bola@example.com", "seller.catalog");
        ArgumentCaptor<OrgInvitation> updated = ArgumentCaptor.forClass(OrgInvitation.class);
        verify(invitations).update(updated.capture());
        assertThat(updated.getValue().status()).isEqualTo(InvitationStatus.REVOKED);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("identity.invitation.revoked"), eq("identity"), eq(ORG), payload.capture());
        assertThat(((InvitationRevoked) payload.getValue()).reason()).contains("replaced");
    }

    @Test
    void inviteRules() {
        assertThatThrownBy(() -> service().invite(ORG, "kemi@example.com", "provider.finance"))
                .isInstanceOf(IdentityRuleViolation.class);
        UUID existing = UUID.randomUUID();
        when(users.findByEmail("kemi@example.com")).thenReturn(Optional.of(account(existing, "kemi@example.com")));
        when(orgs.memberRole(ORG, existing)).thenReturn(Optional.of("seller.catalog"));
        assertThatThrownBy(() -> service().invite(ORG, "kemi@example.com", "seller.catalog"))
                .isInstanceOf(ConflictException.class);
        caller = user(admin);
        assertThatThrownBy(() -> service().invite(ORG, "tobi@example.com", "seller.owner"))
                .isInstanceOf(ForbiddenActionException.class);
        verify(invitations, never()).insert(any());
    }

    @Test
    void acceptRequiresTheInvitedEmailAndAPendingInvitation() {
        UUID bola = UUID.randomUUID();
        when(users.findById(bola)).thenReturn(Optional.of(account(bola, "bola@example.com")));
        when(orgs.memberRole(ORG, bola)).thenReturn(Optional.empty());
        String token = policy.newToken();
        OrgInvitation inv = invitation("bola@example.com", "seller.catalog", InvitationStatus.PENDING);
        when(invitations.findByTokenHash(policy.hashToken(token))).thenReturn(Optional.of(inv));

        UUID other = UUID.randomUUID();
        when(users.findById(other)).thenReturn(Optional.of(account(other, "someone@example.com")));
        caller = user(other);
        assertThatThrownBy(() -> service().accept(token)).isInstanceOf(IdentityRuleViolation.class)
                .satisfies(e -> assertThat(((IdentityRuleViolation) e).code())
                        .isEqualTo("identity.invitation-email-mismatch"));
        assertThatThrownBy(() -> service().accept("unknown")).isInstanceOf(IdentityRuleViolation.class)
                .satisfies(e -> assertThat(((IdentityRuleViolation) e).code()).isEqualTo("identity.invitation-invalid"));

        caller = user(bola);
        AcceptedInvitation a = service().accept(token);
        assertThat(a.roleKey()).isEqualTo("seller.catalog");
        assertThat(a.alreadyMember()).isFalse();
        verify(orgs).setMember(ORG, bola, "seller.catalog");
        verify(events).publish(eq("identity.role.granted"), eq("identity"), eq(bola.toString()), any());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("identity.invitation.accepted"), eq("identity"), eq(bola.toString()),
                payload.capture());
        assertThat(((InvitationAccepted) payload.getValue()).orgId()).isEqualTo(ORG);

        OrgInvitation expired = new OrgInvitation(UUID.randomUUID(), ORG, "bola@example.com", "seller.catalog", "x",
                InvitationStatus.PENDING, owner.toString(), NOW.minus(Duration.ofDays(8)),
                NOW.minus(Duration.ofDays(1)), null, null, null, null);
        when(invitations.findByTokenHash(policy.hashToken("old"))).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service().accept("old")).isInstanceOf(IdentityRuleViolation.class)
                .satisfies(e -> assertThat(((IdentityRuleViolation) e).code()).isEqualTo("identity.invitation-expired"));
    }

    @Test
    void revokeOnlyPendingInvitationsOfTheOrganisation() {
        OrgInvitation inv = invitation("bola@example.com", "seller.catalog", InvitationStatus.PENDING);
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));
        service().revoke(ORG, inv.id());
        verify(invitations).update(any(OrgInvitation.class));
        OrgInvitation done = invitation("bola@example.com", "seller.catalog", InvitationStatus.ACCEPTED);
        when(invitations.findById(done.id())).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service().revoke(ORG, done.id())).isInstanceOf(ConflictException.class);
        when(invitations.pending(ORG, NOW)).thenReturn(List.of(inv));
        assertThat(service().pending(ORG)).extracting(InvitationView::email).containsExactly("bola@example.com");
    }

    private OrgInvitation invitation(String email, String role, InvitationStatus status) {
        return new OrgInvitation(UUID.randomUUID(), ORG, email, role, "hash-" + UUID.randomUUID(), status,
                owner.toString(), NOW.minusSeconds(60), NOW.plus(Duration.ofDays(6)), null, null, null, null);
    }

    private static Actor user(UUID id) {
        return new Actor(id.toString(), PrincipalType.SELLER_MEMBER, Optional.empty(), Set.of(), Set.of());
    }

    private static UserAccount account(UUID id, String email) {
        return new UserAccount(id, "Ada", "Okafor", email, true, "+17132490142", "h", true, false, NOW, null, NOW,
                NOW, 0);
    }
}
