package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.AddressBookPolicy.AddressDraft;
import com.afrotransact.identity.domain.DefaultAccessPolicy;
import com.afrotransact.identity.domain.DefaultTeamPolicy;
import com.afrotransact.identity.domain.InvitationStatus;
import com.afrotransact.identity.domain.ModuleAccess;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgMember;
import com.afrotransact.identity.domain.OrgInvitation;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.PostalAddress;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.PushApp;
import com.afrotransact.identity.domain.DevicePlatform;
import com.afrotransact.identity.domain.PushDevice;
import com.afrotransact.identity.domain.PushProvider;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.AddressRepository;
import com.afrotransact.identity.port.ModuleAccessRepository;
import com.afrotransact.identity.port.OrgInvitationRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.PushDeviceRepository;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.SessionStore;
import com.afrotransact.identity.port.TourProgressRepository;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.identity.port.SellerOrgSource;
import com.afrotransact.identity.port.SellerOrgSource.SellerOrgState;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-tenant proof for identity (tenant-isolation-matrix.txt). Identity does not trust the token {@code org} claim
 * for team routes: membership of the path organisation is re-read from the store on every call. Tenant A's owner
 * attacks tenant B's organisation, members, invitations and personal data.
 */
class IdentityTenantIsolationTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String A = TenantFixture.ORG_A;
    private static final String B = TenantFixture.ORG_B;

    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final ModuleAccessRepository modules = mock(ModuleAccessRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final OrgInvitationRepository invitations = mock(OrgInvitationRepository.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final DefaultTeamPolicy policy = new DefaultTeamPolicy();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID ownerA = UUID.randomUUID();
    private final UUID ownerB = UUID.randomUUID();
    private final UUID memberB = UUID.randomUUID();
    private Actor caller;

    private CurrentUser current() {
        return new CurrentUser(() -> caller);
    }

    private OrgTeamAccess access() {
        return new OrgTeamAccess(current(), orgs, roles, modules, policy);
    }

    private MembershipWriter writer() {
        return new MembershipWriter(orgs, users, changeLog, events, clock);
    }

    private OrgTeamServiceImpl team() {
        return new OrgTeamServiceImpl(access(), orgs, roles, users, writer(), policy, current());
    }

    private OrgInvitationServiceImpl invites() {
        InvitationRedeemer redeemer = new InvitationRedeemer(invitations, orgs, writer(), policy, changeLog, events,
                clock);
        return new OrgInvitationServiceImpl(access(), invitations, orgs, roles, users, redeemer, current(), policy,
                new InvitationSettings("https://seller.example.com/invitations/accept"), changeLog, events, clock);
    }

    private static Actor member(UUID id, String org) {
        return new Actor(id.toString(), PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of("team:manage"));
    }

    @BeforeEach
    void twoTenants() {
        for (String org : List.of(A, B)) {
            when(orgs.findById(org)).thenReturn(Optional.of(new Organization(org, OrgKind.SELLER, org,
                    OrgStatus.APPROVED, "US", NOW)));
        }
        when(orgs.memberRole(A, ownerA)).thenReturn(Optional.of("seller.owner"));
        when(orgs.memberRole(B, ownerB)).thenReturn(Optional.of("seller.owner"));
        when(orgs.memberRole(B, memberB)).thenReturn(Optional.of("seller.catalog"));
        when(orgs.members(B)).thenReturn(List.of(new OrgMember(ownerB, "Bola", "Bello", "bola@example.com",
                "seller.owner", "Owner", true, NOW)));
        when(orgs.ownerUserIds(B)).thenReturn(List.of(ownerB));
        when(orgs.setMember(anyString(), any(), anyString())).thenReturn(true);
        when(orgs.removeMember(anyString(), any())).thenReturn(true);
        when(roles.role("seller.owner")).thenReturn(Optional.of(new Role("seller.owner", Portal.SELLER, "Owner", "",
                Set.of("team:manage"))));
        when(roles.role("seller.catalog")).thenReturn(Optional.of(new Role("seller.catalog", Portal.SELLER,
                "Catalog", "", Set.of())));
        when(modules.matrix(Portal.SELLER)).thenReturn(List.<ModuleAccess>of());
        for (UUID id : List.of(ownerA, ownerB, memberB)) {
            when(users.findById(id)).thenReturn(Optional.of(account(id)));
        }
        when(users.update(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
        caller = member(ownerA, A);
    }

    private static UserAccount account(UUID id) {
        return new UserAccount(id, "Ada", "Okafor", id + "@example.com", true, "+17132490142", "h", true, false, NOW,
                null, NOW, NOW, 0);
    }

    // ---- B: membership routes (ORG_SCOPED_LIST / ORG_OWNED) ----------------------------------------------------

    @Test
    void ownerOfAReadsNothingOfBTeamDirectoryOrRoles() {
        CrossTenant.assertDenied(() -> team().members(B));
        CrossTenant.assertDenied(() -> team().assignableRoles(B));
        // and the token org claim is not what decides: a token claiming org B without membership is refused too
        caller = member(ownerA, B);
        CrossTenant.assertDenied(() -> team().members(B));
        CrossTenant.assertDenied(() -> team().setMember(B, memberB, "seller.owner"));
    }

    @Test
    void ownerOfACannotAddChangeOrRemoveMembersOfB() {
        CrossTenant.assertDenied(() -> team().setMember(B, memberB, "seller.owner"));
        CrossTenant.assertDenied(() -> team().setMember(B, ownerA, "seller.owner"));
        CrossTenant.assertDenied(() -> team().removeMember(B, memberB));
        CrossTenant.assertDenied(() -> team().removeMember(B, ownerB));
        verify(orgs, never()).setMember(eq(B), any(), anyString());
        verify(orgs, never()).removeMember(eq(B), any());
    }

    @Test
    void ownerOfACannotUseOrgAPathToTouchAMemberWhoOnlyBelongsToB() {
        CrossTenant.assertDenied(() -> team().setMember(A, memberB, "seller.catalog"));
        CrossTenant.assertDenied(() -> team().removeMember(A, memberB));
        verify(orgs, never()).setMember(anyString(), eq(memberB), anyString());
        verify(orgs, never()).removeMember(anyString(), eq(memberB));
    }

    @Test
    void ownerOfACannotInviteToReadOrRevokeInvitationsOfB() {
        OrgInvitation inviteOfB = new OrgInvitation(UUID.randomUUID(), B, "x@example.com", "seller.catalog", "hash",
                InvitationStatus.PENDING, ownerB.toString(), NOW.minusSeconds(60), NOW.plus(Duration.ofDays(6)), null,
                null, null, null);
        when(invitations.findById(inviteOfB.id())).thenReturn(Optional.of(inviteOfB));
        when(invitations.pending(eq(B), any())).thenReturn(List.of(inviteOfB));

        CrossTenant.assertDenied(() -> invites().invite(B, "new@example.com", "seller.catalog"));
        CrossTenant.assertDenied(() -> invites().pending(B));
        CrossTenant.assertDenied(() -> invites().revoke(B, inviteOfB.id()));
        // own org path with B's invitation id: the invitation must belong to the path org
        CrossTenant.assertDenied(() -> invites().revoke(A, inviteOfB.id()));
        verify(invitations, never()).insert(any());
        verify(invitations, never()).update(any());
    }

    // ---- active org switch (CUSTOMER_SELF) ---------------------------------------------------------------------

    @Test
    void activeOrgSwitchVerifiesMembershipServerSide() {
        UserAccessRepository access = mock(UserAccessRepository.class);
        when(access.roleKeys(ownerA)).thenReturn(Set.of());
        when(access.directGrants(ownerA)).thenReturn(Set.of());
        when(orgs.memberships(ownerA)).thenReturn(List.of(new OrgMembership(A, OrgKind.SELLER, A,
                OrgStatus.APPROVED, "seller.owner")));
        when(roles.roles(anySet())).thenReturn(Map.of());
        AccessProfileLoader loader = new AccessProfileLoader(access, roles, orgs);
        ProfileServiceImpl profile = new ProfileServiceImpl(current(), users, access, loader, new DefaultAccessPolicy(),
                null, null, null, changeLog, null, events, clock);

        CrossTenant.assertDenied(() -> profile.selectOrg(B));
        verify(users, never()).update(any(UserAccount.class));
    }

    @Test
    void leaveIsForOwnMembershipOnly() {
        caller = member(ownerA, A);
        CrossTenant.assertDenied(() -> team().leave(B));
        verify(orgs, never()).removeMember(eq(B), any());
    }

    @Test
    void syncOfAnotherOrganisationLooksLikeNotFound() {
        SellerOrgSource source = mock(SellerOrgSource.class);
        when(source.org(B)).thenReturn(Optional.of(new SellerOrgState(B, "SELLER", "SELLER", "B Ltd", "B", "US",
                "APPROVED", List.of(ownerB.toString()), ownerB.toString(), NOW)));
        OrgReconcileServiceImpl reconcile = new OrgReconcileServiceImpl(source, mock(SellerOrgSyncService.class), orgs,
                current());
        CrossTenant.assertDenied(() -> reconcile.sync(B));
    }

    // ---- CUSTOMER_SELF routes addressed by id --------------------------------------------------------------------

    @Test
    void addressesOfAnotherAccountAreInvisibleAndImmutable() {
        UUID other = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        PostalAddress theirs = new PostalAddress(UUID.randomUUID(), other, null, "Bola Bello", "+17132490142",
                "1 Main", null, "Houston", "TX", "77001", "US", null, null, null, true, true, NOW, NOW);
        AddressRepository addresses = mock(AddressRepository.class);
        when(addresses.findById(any(UUID.class), any(UUID.class))).thenAnswer(i -> {
            UUID user = i.getArgument(0);
            return user.equals(theirs.userId()) ? Optional.of(theirs) : Optional.empty();
        });
        when(addresses.findByUser(other)).thenReturn(List.of(theirs));
        caller = new Actor(mine.toString(), PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of());
        AddressBookServiceImpl book = new AddressBookServiceImpl(current(), addresses, null, null, clock);
        AddressDraft draft = new AddressDraft(null, "Hacker", "+17132490142", "US", "9 Elm", null, "Houston", "TX",
                "77001", "US", null);

        CrossTenant.assertDenied(() -> book.update(theirs.id(), draft, null, true));
        CrossTenant.assertDenied(() -> book.remove(theirs.id()));
        CrossTenant.assertDenied(() -> book.makeDefault(theirs.id()));
        assertThat(book.list()).isEmpty();
        verify(addresses, never()).update(any());
        verify(addresses, never()).delete(any(), any());
        verify(addresses, never()).setDefault(any(), any());
    }

    @Test
    void staffAddressLookupNeedsAPermissionAMemberTokenLacks() {
        CrossTenant.assertDenied(() -> new AddressBookServiceImpl(current(), mock(AddressRepository.class), null, null,
                clock).listOf(ownerB));
    }

    @Test
    void devicesOfAnotherAccountCannotBeUnregistered() {
        UUID other = UUID.randomUUID();
        PushDevice theirs = new PushDevice(UUID.randomUUID(), other, PushApp.CUSTOMER, DevicePlatform.IOS,
                PushProvider.FCM, "token", "hash", "phone", NOW, NOW);
        PushDeviceRepository devices = mock(PushDeviceRepository.class);
        when(devices.findByUser(other)).thenReturn(List.of(theirs));
        when(devices.findByUser(ownerA)).thenReturn(List.of());
        DeviceServiceImpl service = new DeviceServiceImpl(current(), devices, events, clock);

        CrossTenant.assertDenied(() -> service.remove(theirs.id()));
        assertThat(service.list()).isEmpty();
        verify(devices, never()).delete(any(), any());
    }

    @Test
    void sessionsOfAnotherAccountCannotBeSignedOut() {
        SessionStore sessions = mock(SessionStore.class);
        when(sessions.revoke(eq(ownerA), anyString())).thenReturn(false); // user-scoped delete matches no row
        SecurityServiceImpl security = new SecurityServiceImpl(current(), users, null, null, sessions, null, null,
                changeLog, null, clock);
        CrossTenant.assertDenied(() -> security.revokeSession("authorization-of-someone-else"));
        verify(sessions).revoke(ownerA, "authorization-of-someone-else");
    }

    @Test
    void tourProgressResetTouchesOnlyTheCallersRows() {
        TourProgressRepository repo = mock(TourProgressRepository.class);
        TourProgressServiceImpl tours = new TourProgressServiceImpl(current(), repo, null, clock, true);
        tours.reset("first-tour");
        verify(repo).delete(ownerA, "first-tour");
        tours.resetAll();
        verify(repo).deleteAll(ownerA);
    }
}
