package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.RoleChanged;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.OrgIds;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrganizationServiceImpl implements OrganizationService {

    private final PermissionChecker permissions;
    private final OrganizationRepository orgs;
    private final RoleRepository roles;
    private final UserAccountRepository users;
    private final AccessChangeLog changeLog;
    private final DomainEventPublisher events;
    private final Clock clock;

    OrganizationServiceImpl(PermissionChecker permissions, OrganizationRepository orgs, RoleRepository roles,
                            UserAccountRepository users, AccessChangeLog changeLog, DomainEventPublisher events,
                            Clock clock) {
        this.permissions = permissions;
        this.orgs = orgs;
        this.roles = roles;
        this.users = users;
        this.changeLog = changeLog;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Organization upsert(String orgId, OrgKind kind, String name, OrgStatus status) {
        Actor actor = permissions.require(IdentityPermissions.ORGS_MANAGE);
        OrgIds.requireValid(orgId);
        Organization saved = orgs.upsert(new Organization(orgId, kind, name.trim(), status, null,
                Instant.now(clock)));
        changeLog.append(actor.subject(), "org.upserted", null, null, orgId, kind + " " + status);
        return saved;
    }

    @Override
    @Transactional
    public void setMember(String orgId, UUID userId, String roleKey) {
        Actor actor = permissions.require(IdentityPermissions.ORGS_MANAGE);
        Organization org = orgs.findById(orgId).orElseThrow(() -> new NotFoundException("identity.org-not-found",
                "Organisation not found"));
        UserAccount user = users.findById(userId).orElseThrow(() -> new NotFoundException(
                "identity.account-not-found", "Account not found"));
        Role role = roles.role(roleKey).orElseThrow(() -> new NotFoundException("identity.role-not-found",
                "Unknown role " + roleKey));
        Portal expected = org.kind() == OrgKind.SELLER ? Portal.SELLER : Portal.PROVIDER;
        if (role.portal() != expected) {
            throw new IdentityRuleViolation("identity.wrong-portal", "Use a " + expected.wire() + " role");
        }
        if (orgs.setMember(orgId, userId, roleKey)) {
            Instant now = Instant.now(clock);
            changeLog.append(actor.subject(), "org.member.set", userId, roleKey, orgId, org.name());
            events.publish(IdentityEvents.ROLE_GRANTED, IdentityEvents.AGGREGATE, userId.toString(),
                    new RoleChanged(userId.toString(), roleKey, orgId, actor.subject(), "org membership",
                            now));
            if (user.activeOrgId() == null) {
                users.update(user.withActiveOrg(orgId, now));
            }
        }
    }

    @Override
    @Transactional
    public void removeMember(String orgId, UUID userId) {
        Actor actor = permissions.require(IdentityPermissions.ORGS_MANAGE);
        String role = orgs.memberships(userId).stream().filter(m -> m.orgId().equals(orgId))
                .map(OrgMembership::roleKey).findFirst().orElse(null);
        if (role != null && orgs.removeMember(orgId, userId)) {
            Instant now = Instant.now(clock);
            changeLog.append(actor.subject(), "org.member.removed", userId, role, orgId, null);
            events.publish(IdentityEvents.ROLE_REVOKED, IdentityEvents.AGGREGATE, userId.toString(),
                    new RoleChanged(userId.toString(), role, orgId, actor.subject(), "org membership",
                            now));
            users.findById(userId).filter(u -> orgId.equals(u.activeOrgId()))
                    .ifPresent(u -> users.update(u.withActiveOrg(null, now)));
        }
    }
}
