package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.InvitationCreated;
import com.afrotransact.identity.application.IdentityEventPayloads.InvitationRevoked;
import com.afrotransact.identity.application.IdentityEventPayloads.Recipient;
import com.afrotransact.identity.application.OrgTeamAccess.TeamCaller;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.InvitationStatus;
import com.afrotransact.identity.domain.OrgInvitation;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.TeamPolicy;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OrgInvitationRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgInvitationServiceImpl implements OrgInvitationService {

    private final OrgTeamAccess access;
    private final OrgInvitationRepository invitations;
    private final OrganizationRepository orgs;
    private final RoleRepository roles;
    private final UserAccountRepository users;
    private final InvitationRedeemer redeemer;
    private final CurrentUser current;
    private final TeamPolicy policy;
    private final InvitationSettings settings;
    private final AccessChangeLog changeLog;
    private final DomainEventPublisher events;
    private final Clock clock;

    OrgInvitationServiceImpl(OrgTeamAccess access, OrgInvitationRepository invitations, OrganizationRepository orgs,
                             RoleRepository roles, UserAccountRepository users, InvitationRedeemer redeemer,
                             CurrentUser current, TeamPolicy policy, InvitationSettings settings,
                             AccessChangeLog changeLog, DomainEventPublisher events, Clock clock) {
        this.access = access;
        this.invitations = invitations;
        this.orgs = orgs;
        this.roles = roles;
        this.users = users;
        this.redeemer = redeemer;
        this.current = current;
        this.policy = policy;
        this.settings = settings;
        this.changeLog = changeLog;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public InvitationView invite(String orgId, String email, String roleKey) {
        Organization org = access.org(orgId);
        TeamCaller caller = access.requireManage(org);
        String address = UserAccount.normaliseEmail(email);
        if (address == null || !address.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IdentityRuleViolation("identity.invalid-email", "Enter a valid email address",
                    Map.of("email", "Enter a valid email address"));
        }
        Role role = role(roleKey);
        policy.requireRoleFor(org.kind(), role);
        access.requireOwnerChangeAllowed(caller, roleKey);
        boolean member = users.findByEmail(address)
                .flatMap(u -> orgs.memberRole(org.id(), u.id())).isPresent();
        if (member) {
            throw new ConflictException("identity.already-member", address + " is already a member of this team");
        }
        Instant now = Instant.now(clock);
        invitations.findPending(org.id(), address).ifPresent(old -> revokeInternal(org, old, caller.subject(),
                "replaced by a new invitation", now));
        String token = policy.newToken();
        OrgInvitation inv = new OrgInvitation(UUID.randomUUID(), org.id(), address, role.key(),
                policy.hashToken(token), InvitationStatus.PENDING, caller.subject(), now,
                now.plus(TeamPolicy.INVITATION_TTL), null, null, null, null);
        invitations.insert(inv);
        changeLog.append(caller.subject(), "org.invitation.created", null, role.key(), org.id(), address);
        String inviterName = caller.userId() == null ? null
                : users.findById(caller.userId()).map(UserAccount::displayName).orElse(null);
        events.publish(IdentityEvents.INVITATION_CREATED, IdentityEvents.AGGREGATE, org.id(),
                new InvitationCreated(inv.id().toString(), org.id(), org.name(), org.kind().name(), address,
                        role.key(), role.name(), token, settings.link(token), inv.expiresAt(),
                        TeamPolicy.INVITATION_TTL.toDays(), caller.subject(), inviterName,
                        new Recipient(null, address, null), true));
        return view(inv, org, role.name(), now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvitationView> pending(String orgId) {
        Organization org = access.org(orgId);
        access.requireRead(org);
        Instant now = Instant.now(clock);
        return invitations.pending(org.id(), now).stream()
                .map(i -> view(i, org, roleName(i.roleKey()), now)).toList();
    }

    @Override
    @Transactional
    public void revoke(String orgId, UUID invitationId) {
        Organization org = access.org(orgId);
        TeamCaller caller = access.requireManage(org);
        OrgInvitation inv = invitations.findById(invitationId).filter(i -> i.orgId().equals(org.id()))
                .orElseThrow(() -> new NotFoundException("identity.invitation-not-found", "Invitation not found"));
        Instant now = Instant.now(clock);
        if (!inv.pendingAt(now)) {
            throw new ConflictException("identity.invitation-not-pending",
                    "This invitation is " + inv.statusAt(now).name().toLowerCase(Locale.ROOT) + " and can't be revoked");
        }
        access.requireOwnerChangeAllowed(caller, inv.roleKey());
        revokeInternal(org, inv, caller.subject(), "revoked", now);
    }

    @Override
    @Transactional
    public AcceptedInvitation accept(String token) {
        UserAccount user = users.findById(current.id()).orElseThrow(() -> new NotFoundException(
                "identity.account-not-found", "Account not found"));
        return redeemer.redeem(redeemer.requirePending(token, user.email()), user);
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationView preview(String token) {
        OrgInvitation inv = invitations.findByTokenHash(policy.hashToken(token)).orElseThrow(() ->
                new NotFoundException("identity.invitation-not-found", "This invitation link isn't valid"));
        Organization org = access.org(inv.orgId());
        return view(inv, org, roleName(inv.roleKey()), Instant.now(clock));
    }

    private void revokeInternal(Organization org, OrgInvitation inv, String actor, String reason, Instant now) {
        invitations.update(inv.revoked(actor, now));
        changeLog.append(actor, "org.invitation.revoked", null, inv.roleKey(), org.id(), inv.email());
        events.publish(IdentityEvents.INVITATION_REVOKED, IdentityEvents.AGGREGATE, org.id(),
                new InvitationRevoked(inv.id().toString(), org.id(), inv.email(), inv.roleKey(), actor, reason, now));
    }

    private Role role(String roleKey) {
        return roles.role(roleKey).orElseThrow(() -> new IdentityRuleViolation("identity.invalid-role",
                "Unknown role " + roleKey, Map.of("roleKey", "Choose a role of this organisation")));
    }

    private String roleName(String roleKey) {
        return roles.role(roleKey).map(Role::name).orElse(roleKey);
    }

    private static InvitationView view(OrgInvitation i, Organization org, String roleName, Instant now) {
        return new InvitationView(i.id(), org.id(), org.name(), org.kind(), i.email(), i.roleKey(), roleName,
                i.statusAt(now), i.invitedBy(), i.createdAt(), i.expiresAt());
    }
}
