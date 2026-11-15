package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.StaffInvitationCreated;
import com.afrotransact.identity.domain.DefaultPasswordPolicy;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.InvitationStatus;
import com.afrotransact.identity.domain.PhoneNumberPolicy;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.domain.SignUpPolicy;
import com.afrotransact.identity.domain.StaffInvitation;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.StaffInvitationRepository;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StaffInvitationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("0a0a0a0a-0000-4000-8000-000000000001");
    private static final Actor ADMIN = new Actor(ADMIN_ID.toString(), PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.customer_support"), Set.of("access:admin", "access:read"));
    private static final Actor SUPER = new Actor(ADMIN_ID.toString(), PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("access:admin", "access:read"));
    private static final Actor READER = new Actor("reader", PrincipalType.STAFF, Optional.empty(), Set.of(),
            Set.of("access:read"));
    private static final Role SUPPORT = new Role("platform.customer_support", Portal.PLATFORM, "Customer Support", "",
            Set.of());
    private static final Role SUPER_ADMIN = new Role("platform.super_admin", Portal.PLATFORM, "Super Admin", "", Set.of());
    private static final Role SELLER_OWNER = new Role("seller.owner", Portal.SELLER, "Owner", "", Set.of());
    private static final String REASON = "Joins customer support in the Lagos office";
    private static final String PASSWORD = "Plantain-Jollof-2026";

    private final CurrentUser current = mock(CurrentUser.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final StaffInvitationRepository invitations = mock(StaffInvitationRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final UserAccessRepository access = mock(UserAccessRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final SignUpPolicy signUp = mock(SignUpPolicy.class);
    private final PhoneNumberPolicy phones = mock(PhoneNumberPolicy.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final StaffInvitationServiceImpl service = new StaffInvitationServiceImpl(current, permissions, invitations,
            users, access, roles, changeLog, signUp, new DefaultPasswordPolicy(() -> 12), phones, hasher,
            new StaffInvitationSettings("https://admin.example.com/invite", null, false, List.of("afrotransact.com")),
            events, clock);

    StaffInvitationServiceImplTest() {
        when(permissions.require(IdentityPermissions.ACCESS_ADMIN)).thenReturn(ADMIN);
        when(current.actor()).thenReturn(READER);
        when(signUp.validName(anyString())).thenReturn(true);
        when(roles.role("platform.customer_support")).thenReturn(Optional.of(SUPPORT));
        when(roles.role("platform.super_admin")).thenReturn(Optional.of(SUPER_ADMIN));
        when(roles.role("seller.owner")).thenReturn(Optional.of(SELLER_OWNER));
        when(hasher.hash(anyString())).thenReturn("hashed-password");
        when(users.findById(ADMIN_ID)).thenReturn(Optional.of(account(ADMIN_ID, "Ifeoma", "Okafor", "ifeoma@afrotransact.com")));
        when(users.insert(any(UserAccount.class))).thenAnswer(i -> i.getArgument(0));
        when(invitations.claim(any(), any(), any())).thenReturn(true);
    }

    private static UserAccount account(UUID id, String first, String last, String email) {
        return new UserAccount(id, first, last, email, true, null, "h", true, false, NOW, null, NOW, NOW, 0);
    }

    private InviteStaffCommand invite() {
        return new InviteStaffCommand("Ada", "Obi", " Ada.Obi@AfroTransact.com ", "platform.customer_support", REASON);
    }

    /** Invites and returns the raw token the email carries (read back from the link in the event). */
    private String inviteAndReadToken() {
        service.invite(invite());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(IdentityEvents.STAFF_INVITATION_CREATED), anyString(), anyString(), payload.capture());
        String link = ((StaffInvitationCreated) payload.getValue()).acceptUrl();
        assertThat(link).startsWith("https://admin.example.com/invite#token=");
        return link.substring(link.indexOf("#token=") + "#token=".length());
    }

    private StaffInvitation stored(String token, InvitationStatus status, Instant expires) {
        return new StaffInvitation(UUID.randomUUID(), "ada.obi@afrotransact.com", "Ada", "Obi",
                "platform.customer_support", REASON, SecretHashing.sha256Hex(token), status, ADMIN_ID.toString(),
                NOW, expires, NOW, 1, null, null, null, null);
    }

    @Test
    void inviteStoresOnlyAHashOfTheTokenExpiresInSeventyTwoHoursAndIsAudited() {
        String token = inviteAndReadToken();

        ArgumentCaptor<StaffInvitation> saved = ArgumentCaptor.forClass(StaffInvitation.class);
        verify(invitations).insert(saved.capture());
        StaffInvitation inv = saved.getValue();
        assertThat(inv.tokenHash()).isEqualTo(SecretHashing.sha256Hex(token)).doesNotContain(token);
        assertThat(inv.email()).isEqualTo("ada.obi@afrotransact.com");
        assertThat(inv.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(inv.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(72)));
        assertThat(token).hasSizeGreaterThanOrEqualTo(40);
        verify(changeLog).append(ADMIN_ID.toString(), "staff.invitation.created", null, "platform.customer_support",
                "ada.obi@afrotransact.com", REASON);
        // the invitation row, the audit trail and the event never hold the token or a password
        verify(changeLog, never()).append(anyString(), anyString(), any(), any(), any(), contains(token));
    }

    @Test
    void inviteIsRefusedForAnOrganisationRoleAForeignDomainAShortReasonOrAnExistingAccount() {
        assertThatThrownBy(() -> service.invite(new InviteStaffCommand("Ada", "Obi", "ada@afrotransact.com", "seller.owner", REASON)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, e -> assertThat(e.fieldErrors()).containsKey("roleKey"));
        assertThatThrownBy(() -> service.invite(new InviteStaffCommand("Ada", "Obi", "ada@gmail.com", "platform.customer_support", REASON)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, e -> assertThat(e.fieldErrors()).containsKey("email"));
        assertThatThrownBy(() -> service.invite(new InviteStaffCommand("Ada", "Obi", "ada@afrotransact.com", "platform.customer_support", "no")))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, e -> assertThat(e.fieldErrors()).containsKey("reason"));
        when(users.findByEmail("ada.obi@afrotransact.com")).thenReturn(Optional.of(account(UUID.randomUUID(), "Ada", "Obi", "ada.obi@afrotransact.com")));
        assertThatThrownBy(() -> service.invite(invite())).isInstanceOf(ConflictException.class);
        verify(invitations, never()).insert(any());
        verify(events, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void onlyASuperAdminCanInviteASuperAdmin() {
        InviteStaffCommand c = new InviteStaffCommand("Ada", "Obi", "ada@afrotransact.com", "platform.super_admin", REASON);
        assertThatThrownBy(() -> service.invite(c)).isInstanceOf(ForbiddenActionException.class);
        when(permissions.require(IdentityPermissions.ACCESS_ADMIN)).thenReturn(SUPER);
        assertThat(service.invite(c).roleKey()).isEqualTo("platform.super_admin");
    }

    @Test
    void invitingAgainReplacesThePendingInvitationForThatEmail() {
        StaffInvitation old = stored("old-token", InvitationStatus.PENDING, NOW.plus(Duration.ofHours(10)));
        when(invitations.findPendingByEmail("ada.obi@afrotransact.com")).thenReturn(Optional.of(old));

        service.invite(invite());

        verify(invitations).revoke(any(StaffInvitation.class));
        verify(changeLog).append(ADMIN_ID.toString(), "staff.invitation.revoked", null, "platform.customer_support",
                "ada.obi@afrotransact.com", "replaced by a new invitation");
    }

    @Test
    void resendIssuesANewTokenAndExpiryAndTheOldLinkNoLongerMatches() {
        StaffInvitation inv = stored("old-token", InvitationStatus.PENDING, NOW.plus(Duration.ofHours(1)));
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));
        when(invitations.reissue(any())).thenReturn(true);
        clock.advance(Duration.ofHours(2));

        StaffInvitationView view = service.resend(inv.id());

        ArgumentCaptor<StaffInvitation> next = ArgumentCaptor.forClass(StaffInvitation.class);
        verify(invitations).reissue(next.capture());
        assertThat(next.getValue().tokenHash()).isNotEqualTo(inv.tokenHash());
        assertThat(next.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(74)));
        assertThat(next.getValue().sendCount()).isEqualTo(2);
        assertThat(view.sendCount()).isEqualTo(2);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(IdentityEvents.STAFF_INVITATION_CREATED), anyString(), anyString(), payload.capture());
        assertThat(((StaffInvitationCreated) payload.getValue()).resent()).isTrue();
        verify(changeLog).append(eq(ADMIN_ID.toString()), eq("staff.invitation.resent"), any(), anyString(), anyString(), anyString());
    }

    @Test
    void anAcceptedRevokedOrMissingInvitationCannotBeResentOrRevoked() {
        StaffInvitation used = stored("t", InvitationStatus.ACCEPTED, NOW.plus(Duration.ofHours(1)));
        when(invitations.findById(used.id())).thenReturn(Optional.of(used));
        assertThatThrownBy(() -> service.resend(used.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.revoke(used.id(), REASON)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.resend(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void revokeNeedsAReasonAndIsAudited() {
        StaffInvitation inv = stored("t", InvitationStatus.PENDING, NOW.plus(Duration.ofHours(1)));
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));
        when(invitations.revoke(any())).thenReturn(true);

        assertThatThrownBy(() -> service.revoke(inv.id(), " ")).isInstanceOf(IdentityRuleViolation.class);
        service.revoke(inv.id(), "Offer withdrawn");

        verify(changeLog).append(ADMIN_ID.toString(), "staff.invitation.revoked", null, "platform.customer_support",
                "ada.obi@afrotransact.com", "Offer withdrawn");
    }

    @Test
    void acceptCreatesTheAccountWithTheInvitedRoleAndTheInviteesOwnPasswordAndSpendsTheToken() {
        StaffInvitation inv = stored("good-token", InvitationStatus.PENDING, NOW.plus(Duration.ofHours(5)));
        when(invitations.findByTokenHash(SecretHashing.sha256Hex("good-token"))).thenReturn(Optional.of(inv));
        when(access.grantRole(any(), eq("platform.customer_support"), anyString())).thenReturn(true);

        AcceptedStaffInvitation done = service.accept(new AcceptStaffInvitationCommand(" good-token ", PASSWORD, PASSWORD, null, null));

        ArgumentCaptor<UserAccount> created = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).insert(created.capture());
        assertThat(created.getValue().email()).isEqualTo("ada.obi@afrotransact.com");
        assertThat(created.getValue().passwordHash()).isEqualTo("hashed-password");
        assertThat(created.getValue().enabled()).isTrue();
        assertThat(created.getValue().emailVerified()).isTrue();
        verify(hasher).hash(PASSWORD);
        verify(invitations).claim(inv.id(), created.getValue().id(), NOW);
        verify(access).grantRole(created.getValue().id(), "platform.customer_support", ADMIN_ID.toString());
        verify(changeLog).append(eq(created.getValue().id().toString()), eq("staff.invitation.accepted"), any(), anyString(), anyString(), anyString());
        assertThat(done.roleName()).isEqualTo("Customer Support");
        assertThat(done.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void acceptRefusesAWeakPasswordAMismatchAndAnExpiredOrWithdrawnLinkWithoutWritingAnything() {
        StaffInvitation pending = stored("p", InvitationStatus.PENDING, NOW.plus(Duration.ofHours(5)));
        when(invitations.findByTokenHash(SecretHashing.sha256Hex("p"))).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> service.accept(new AcceptStaffInvitationCommand("p", "short", "short", null, null)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, e -> assertThat(e.fieldErrors()).containsKey("password"));
        assertThatThrownBy(() -> service.accept(new AcceptStaffInvitationCommand("p", PASSWORD, PASSWORD + "x", null, null)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, e -> assertThat(e.fieldErrors()).containsKey("confirmPassword"));
        // the password may not contain the invitee's own name
        assertThatThrownBy(() -> service.accept(new AcceptStaffInvitationCommand("p", "Ada-Obi-Password-1", "Ada-Obi-Password-1", null, null)))
                .isInstanceOf(IdentityRuleViolation.class);

        StaffInvitation expired = stored("e", InvitationStatus.PENDING, NOW.minusSeconds(1));
        when(invitations.findByTokenHash(SecretHashing.sha256Hex("e"))).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.accept(new AcceptStaffInvitationCommand("e", PASSWORD, PASSWORD, null, null)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, e -> assertThat(e.code()).isEqualTo("identity.staff-invitation-expired"));
        StaffInvitation revoked = stored("r", InvitationStatus.REVOKED, NOW.plus(Duration.ofHours(5)));
        when(invitations.findByTokenHash(SecretHashing.sha256Hex("r"))).thenReturn(Optional.of(revoked));
        assertThatThrownBy(() -> service.accept(new AcceptStaffInvitationCommand("r", PASSWORD, PASSWORD, null, null)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, e -> assertThat(e.code()).isEqualTo("identity.staff-invitation-revoked"));
        assertThatThrownBy(() -> service.accept(new AcceptStaffInvitationCommand("nope", PASSWORD, PASSWORD, null, null)))
                .isInstanceOf(NotFoundException.class);
        verify(users, never()).insert(any());
        verify(invitations, never()).claim(any(), any(), any());
    }

    @Test
    void aSecondAcceptWhoseClaimLosesTheRaceIsRefused() {
        StaffInvitation pending = stored("p", InvitationStatus.PENDING, NOW.plus(Duration.ofHours(5)));
        when(invitations.findByTokenHash(SecretHashing.sha256Hex("p"))).thenReturn(Optional.of(pending));
        when(invitations.claim(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.accept(new AcceptStaffInvitationCommand("p", PASSWORD, PASSWORD, null, null)))
                .isInstanceOf(ConflictException.class);
        verify(access, never()).grantRole(any(), anyString(), anyString());
    }

    @Test
    void previewShowsNameEmailRoleAndStatusAndNeverTheTokenOrAnId() {
        StaffInvitation expired = stored("e", InvitationStatus.PENDING, NOW.minusSeconds(1));
        when(invitations.findByTokenHash(SecretHashing.sha256Hex("e"))).thenReturn(Optional.of(expired));

        StaffInvitationPreview p = service.preview("e");

        assertThat(p.status()).isEqualTo(InvitationStatus.EXPIRED);
        assertThat(p.roleName()).isEqualTo("Customer Support");
        assertThat(p.firstName()).isEqualTo("Ada");
    }

    @Test
    void listingNeedsAccessReadAndPagesWithAnExactTotal() {
        when(invitations.open(20, 20)).thenReturn(List.of(stored("t", InvitationStatus.PENDING, NOW.plus(Duration.ofHours(1)))));
        when(invitations.countOpen()).thenReturn(21L);

        PageView<StaffInvitationView> page = service.open(1, 20);

        assertThat(page.total()).isEqualTo(21);
        assertThat(page.items().getFirst().invitedByName()).isEqualTo("Ifeoma Okafor");
        assertThat(page.items().getFirst().roleName()).isEqualTo("Customer Support");
        when(current.actor()).thenReturn(new Actor("x", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of()));
        assertThatThrownBy(() -> service.open(0, 20)).isInstanceOf(ForbiddenActionException.class);
    }
}
