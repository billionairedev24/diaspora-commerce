package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.RoleChanged;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Writes organisation memberships with their audit trail and {@code identity.role.granted} /
 * {@code identity.role.revoked} events (a role change publishes both). Callers check authorisation and team rules.
 */
@Component
class MembershipWriter {

    private final OrganizationRepository orgs;
    private final UserAccountRepository users;
    private final AccessChangeLog changeLog;
    private final DomainEventPublisher events;
    private final Clock clock;

    MembershipWriter(OrganizationRepository orgs, UserAccountRepository users, AccessChangeLog changeLog,
                     DomainEventPublisher events, Clock clock) {
        this.orgs = orgs;
        this.users = users;
        this.changeLog = changeLog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Adds the user with {@code roleKey}, or changes their role from {@code previousRole}. A user without an active
     * organisation gets this one.
     *
     * @return true when something changed
     */
    boolean set(Organization org, UserAccount user, String previousRole, String roleKey, String actorId,
                String reason) {
        if (!orgs.setMember(org.id(), user.id(), roleKey)) {
            return false;
        }
        Instant now = Instant.now(clock);
        String userId = user.id().toString();
        if (previousRole == null) {
            changeLog.append(actorId, "org.member.set", user.id(), roleKey, org.id(), org.name());
        } else {
            changeLog.append(actorId, "org.member.role-changed", user.id(), roleKey, org.id(),
                    org.name() + " (was " + previousRole + ")");
            events.publish(IdentityEvents.ROLE_REVOKED, IdentityEvents.AGGREGATE, userId,
                    new RoleChanged(userId, previousRole, org.id(), actorId, reason, now, org.market()));
        }
        events.publish(IdentityEvents.ROLE_GRANTED, IdentityEvents.AGGREGATE, userId,
                new RoleChanged(userId, roleKey, org.id(), actorId, reason, now, org.market()));
        if (user.activeOrgId() == null) {
            users.findById(user.id()).filter(u -> u.activeOrgId() == null)
                    .ifPresent(u -> users.update(u.withActiveOrg(org.id(), now)));
        }
        return true;
    }

    /** Removes the membership; a user acting for this organisation loses it as their active organisation. */
    boolean remove(Organization org, UUID userId, String role, String actorId, String reason) {
        if (!orgs.removeMember(org.id(), userId)) {
            return false;
        }
        Instant now = Instant.now(clock);
        changeLog.append(actorId, "org.member.removed", userId, role, org.id(), null);
        events.publish(IdentityEvents.ROLE_REVOKED, IdentityEvents.AGGREGATE, userId.toString(),
                new RoleChanged(userId.toString(), role, org.id(), actorId, reason, now, org.market()));
        users.findById(userId).filter(u -> org.id().equals(u.activeOrgId()))
                .ifPresent(u -> users.update(u.withActiveOrg(null, now)));
        return true;
    }
}
