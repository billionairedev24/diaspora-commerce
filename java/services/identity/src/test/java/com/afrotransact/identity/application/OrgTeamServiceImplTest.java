package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.RoleChanged;
import com.afrotransact.identity.domain.AccessLevel;
import com.afrotransact.identity.domain.DefaultTeamPolicy;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.ModuleAccess;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgMember;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.ModuleAccessRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrgTeamServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String ORG = "org_team0000000000000001";

    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final ModuleAccessRepository modules = mock(ModuleAccessRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final DefaultTeamPolicy policy = new DefaultTeamPolicy();

    private final UUID owner = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID catalog = UUID.randomUUID();
    private final UUID finance = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private Actor caller;
    private OrgStatus status = OrgStatus.APPROVED;

    private OrgTeamServiceImpl service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CurrentUser current = new CurrentUser(() -> caller);
        OrgTeamAccess access = new OrgTeamAccess(current, orgs, roles, modules, policy);
        MembershipWriter writer = new MembershipWriter(orgs, users, changeLog, events, clock);
        return new OrgTeamServiceImpl(access, orgs, roles, users, writer, policy, current);
    }

    @BeforeEach
    void team() {
        when(orgs.findById(ORG)).thenAnswer(i -> Optional.of(new Organization(ORG, OrgKind.SELLER, "Mama Put",
                status, "US", NOW)));
        member(owner, "seller.owner");
        member(admin, "seller.admin");
        member(catalog, "seller.catalog");
        member(finance, "seller.finance");
        account(stranger);
        when(orgs.memberRole(ORG, stranger)).thenReturn(Optional.empty());
        role("seller.owner", Portal.SELLER, "team:manage", "catalog:write");
        role("seller.admin", Portal.SELLER, "team:manage");
        role("seller.catalog", Portal.SELLER, "catalog:write");
        role("seller.finance", Portal.SELLER, "payouts:read");
        role("seller.fulfilment", Portal.SELLER, "orders:read");
        role("provider.manager", Portal.PROVIDER, "team:manage");
        when(modules.matrix(Portal.SELLER)).thenReturn(List.of(
                new ModuleAccess(Portal.SELLER, "sTeam", "seller.finance", AccessLevel.VIEW),
                new ModuleAccess(Portal.SELLER, "sTeam", "seller.catalog", AccessLevel.NONE)));
        when(orgs.ownerUserIds(ORG)).thenReturn(List.of(owner));
        when(orgs.setMember(eq(ORG), any(), anyString())).thenReturn(true);
        when(orgs.removeMember(eq(ORG), any())).thenReturn(true);
        when(orgs.members(ORG)).thenReturn(List.of(new OrgMember(owner, "Ada", "Okafor", "ada@example.com",
                "seller.owner", "Owner", true, NOW)));
    }

    @Test
    void membersLeaveOnTheirOwnButOwnersMustHandOverFirst() {
        caller = user(catalog);
        service().leave(ORG);
        verify(orgs).removeMember(ORG, catalog);
        verify(events).publish(eq("identity.role.revoked"), eq("identity"), eq(catalog.toString()), any());
        verify(changeLog).append(eq(catalog.toString()), eq("org.member.removed"), eq(catalog), eq("seller.catalog"),
                eq(ORG), any());

        caller = user(owner);
        assertThatThrownBy(() -> service().leave(ORG)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("Owners can't leave");
        caller = user(stranger);
        assertThatThrownBy(() -> service().leave(ORG)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service().leave("org_missing")).isInstanceOf(NotFoundException.class);
        verify(orgs, never()).removeMember(ORG, owner);
    }

    @Test
    void directoryIsForTeamManagersTeamViewersAndStaff() {
        caller = user(owner);
        assertThat(service().members(ORG)).extracting(OrgMember::displayName).containsExactly("Ada Okafor");
        caller = user(finance);
        assertThat(service().members(ORG)).hasSize(1);
        caller = user(catalog);
        assertThatThrownBy(() -> service().members(ORG)).isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("can't view the team");
        caller = user(stranger);
        assertThatThrownBy(() -> service().members(ORG)).isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("not a member");
        caller = new Actor(UUID.randomUUID().toString(), PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of("orgs:read"));
        assertThat(service().members(ORG)).hasSize(1);
        caller = new Actor(UUID.randomUUID().toString(), PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of("orders:read"));
        assertThatThrownBy(() -> service().members(ORG)).isInstanceOf(ForbiddenActionException.class);
        caller = new Actor("payments-service", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of("orgs:read"));
        assertThat(service().members(ORG)).hasSize(1);
        assertThatThrownBy(() -> service().members("org_missing")).isInstanceOf(NotFoundException.class);
        status = OrgStatus.SUSPENDED;
        caller = user(owner);
        assertThatThrownBy(() -> service().members(ORG)).isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("approved");
    }

    @Test
    void roleChangePublishesRevokedForTheOldRoleAndGrantedForTheNewOne() {
        caller = user(admin);
        service().setMember(ORG, catalog, "seller.fulfilment");
        verify(orgs).setMember(ORG, catalog, "seller.fulfilment");
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publish(types.capture(), eq("identity"), eq(catalog.toString()), payloads.capture());
        assertThat(types.getAllValues()).containsExactly("identity.role.revoked", "identity.role.granted");
        assertThat(payloads.getAllValues()).extracting(p -> ((RoleChanged) p).roleKey())
                .containsExactly("seller.catalog", "seller.fulfilment");
        assertThat(payloads.getAllValues()).extracting(p -> ((RoleChanged) p).orgId()).containsOnly(ORG);
        verify(changeLog).append(eq(admin.toString()), eq("org.member.role-changed"), eq(catalog),
                eq("seller.fulfilment"), eq(ORG), anyString());
    }

    @Test
    void onlyTeamManagersChangeRolesAndOnlyOwnersTouchTheOwnerRole() {
        caller = user(catalog);
        assertThatThrownBy(() -> service().setMember(ORG, finance, "seller.catalog"))
                .isInstanceOf(ForbiddenActionException.class).hasMessageContaining("team:manage");
        caller = user(admin);
        assertThatThrownBy(() -> service().setMember(ORG, catalog, "seller.owner"))
                .isInstanceOf(ForbiddenActionException.class).hasMessageContaining("Only an owner");
        assertThatThrownBy(() -> service().setMember(ORG, owner, "seller.admin"))
                .isInstanceOf(ForbiddenActionException.class).hasMessageContaining("Only an owner");
        assertThatThrownBy(() -> service().removeMember(ORG, owner))
                .isInstanceOf(ForbiddenActionException.class).hasMessageContaining("Only an owner");
        assertThatThrownBy(() -> service().setMember(ORG, admin, "seller.catalog"))
                .isInstanceOf(ForbiddenActionException.class).hasMessageContaining("your own role");
        assertThatThrownBy(() -> service().setMember(ORG, stranger, "seller.catalog"))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("invitation");
        assertThatThrownBy(() -> service().setMember(ORG, catalog, "provider.manager"))
                .isInstanceOf(IdentityRuleViolation.class).hasMessageContaining("seller role");
        assertThatThrownBy(() -> service().setMember(ORG, catalog, "seller.nope"))
                .isInstanceOf(IdentityRuleViolation.class);
        verify(orgs, never()).setMember(anyString(), any(), anyString());
        verify(events, never()).publish(anyString(), anyString(), anyString(), any());

        caller = user(owner);
        service().setMember(ORG, catalog, "seller.owner");
        verify(orgs).setMember(ORG, catalog, "seller.owner");
    }

    @Test
    void theLastOwnerCannotBeRemovedOrDemotedExceptByTheSellerServiceMirror() {
        caller = user(owner);
        assertThatThrownBy(() -> service().removeMember(ORG, owner)).isInstanceOf(IdentityRuleViolation.class)
                .satisfies(e -> assertThat(((IdentityRuleViolation) e).code()).isEqualTo("identity.last-owner"));
        Actor staff = new Actor(UUID.randomUUID().toString(), PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of("orgs:manage"));
        caller = staff;
        assertThatThrownBy(() -> service().setMember(ORG, owner, "seller.admin"))
                .isInstanceOf(IdentityRuleViolation.class);
        verify(orgs, never()).removeMember(anyString(), any());

        UUID second = UUID.randomUUID();
        when(orgs.ownerUserIds(ORG)).thenReturn(List.of(owner, second));
        service().setMember(ORG, owner, "seller.admin");
        verify(orgs).setMember(ORG, owner, "seller.admin");

        when(orgs.ownerUserIds(ORG)).thenReturn(List.of(owner));
        caller = new Actor("seller-service", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of("orgs:manage"));
        service().removeMember(ORG, owner);
        verify(orgs).removeMember(ORG, owner);
        // once for the demotion to seller.admin above, once for the removal
        verify(events, times(2)).publish(eq("identity.role.revoked"), eq("identity"), eq(owner.toString()), any());
    }

    @Test
    void theSellerServiceAddsMembersAndRemovalIsIdempotentForIt() {
        caller = new Actor("seller-service", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of("orgs:manage"));
        service().setMember(ORG, stranger, "seller.catalog");
        verify(orgs).setMember(ORG, stranger, "seller.catalog");
        verify(users).update(any(UserAccount.class));
        verify(events).publish(eq("identity.role.granted"), eq("identity"), eq(stranger.toString()), any());

        service().removeMember(ORG, UUID.randomUUID());
        caller = user(admin);
        assertThatThrownBy(() -> service().removeMember(ORG, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        service().removeMember(ORG, catalog);
        verify(orgs).removeMember(ORG, catalog);
    }

    private Actor user(UUID id) {
        return new Actor(id.toString(), PrincipalType.SELLER_MEMBER, Optional.of(ORG), Set.of(), Set.of());
    }

    private void member(UUID id, String role) {
        account(id);
        when(orgs.memberRole(ORG, id)).thenReturn(Optional.of(role));
    }

    private void account(UUID id) {
        UserAccount u = new UserAccount(id, "Ada", "Okafor", id + "@example.com", true, "+17132490142", "h", true,
                false, NOW, null, NOW, NOW, 0);
        when(users.findById(id)).thenReturn(Optional.of(u));
        when(users.update(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void role(String key, Portal portal, String... perms) {
        when(roles.role(key)).thenReturn(Optional.of(new Role(key, portal, key, "", Set.of(perms))));
    }
}
