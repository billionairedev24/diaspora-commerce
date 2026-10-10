package com.afrotransact.identity.application;

import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessLevel;
import com.afrotransact.identity.domain.ModuleAccess;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.TeamPolicy;
import com.afrotransact.identity.port.ModuleAccessRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * Who may read or manage an organisation's team.
 * <ul>
 *   <li>Staff and services: {@code orgs:read} or {@code orgs:manage} to read, {@code orgs:manage} to change.</li>
 *   <li>Members of an APPROVED organisation, judged by their role in <em>that</em> organisation (not the token's
 *   active org): {@code team:manage} reads and changes; read-only access needs at least VIEW on the
 *   "Team &amp; roles" module ({@code sTeam} / {@code pTeam}) in the module access matrix.</li>
 * </ul>
 */
@Component
class OrgTeamAccess {

    /**
     * The caller as seen by team use cases.
     *
     * @param userId     account id, or null for client-credentials callers
     * @param memberRole the caller's role in the organisation, or null for staff and services
     * @param privileged staff or service (not judged by membership)
     */
    record TeamCaller(Actor actor, UUID userId, String memberRole, boolean privileged) {

        boolean service() {
            return actor.type() == PrincipalType.SERVICE;
        }

        String subject() {
            return actor.subject();
        }
    }

    private final CurrentUser current;
    private final OrganizationRepository orgs;
    private final RoleRepository roles;
    private final ModuleAccessRepository modules;
    private final TeamPolicy policy;

    OrgTeamAccess(CurrentUser current, OrganizationRepository orgs, RoleRepository roles,
                  ModuleAccessRepository modules, TeamPolicy policy) {
        this.current = current;
        this.orgs = orgs;
        this.roles = roles;
        this.modules = modules;
        this.policy = policy;
    }

    Organization org(String orgId) {
        return orgs.findById(orgId).orElseThrow(() -> new NotFoundException("identity.org-not-found",
                "Organisation not found"));
    }

    TeamCaller requireRead(Organization org) {
        return resolve(org, false);
    }

    TeamCaller requireManage(Organization org) {
        return resolve(org, true);
    }

    /** Owners (and staff/services) may grant, change or remove the owner role. */
    void requireOwnerChangeAllowed(TeamCaller caller, String... roleKeys) {
        if (caller.privileged() || policy.isOwnerRole(caller.memberRole())) {
            return;
        }
        for (String key : roleKeys) {
            if (key != null && policy.isOwnerRole(key)) {
                throw new ForbiddenActionException("identity.owner-only",
                        "Only an owner can make someone an owner or change an owner");
            }
        }
    }

    private TeamCaller resolve(Organization org, boolean manage) {
        Actor actor = current.actor();
        if (actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE) {
            boolean allowed = manage ? actor.can(IdentityPermissions.ORGS_MANAGE)
                    : actor.can(IdentityPermissions.ORGS_READ) || actor.can(IdentityPermissions.ORGS_MANAGE);
            if (!allowed) {
                throw new ForbiddenActionException("auth.missing-permission", "Missing permission "
                        + (manage ? IdentityPermissions.ORGS_MANAGE : IdentityPermissions.ORGS_READ));
            }
            return new TeamCaller(actor, uuidOrNull(actor.subject()), null, true);
        }
        UUID userId = current.id();
        String role = orgs.memberRole(org.id(), userId).orElseThrow(() -> new ForbiddenActionException(
                "identity.not-org-member", "You are not a member of this organisation"));
        if (org.status() != OrgStatus.APPROVED) {
            throw new ForbiddenActionException("identity.org-not-approved",
                    "The team can be managed once the organisation is approved");
        }
        Set<String> perms = roles.role(role).map(Role::permissions).orElse(Set.of());
        boolean canManage = perms.contains(IdentityPermissions.TEAM_MANAGE);
        if (manage && !canManage) {
            throw new ForbiddenActionException("identity.team-manage-required",
                    "Your role can't invite members or change roles (team:manage)");
        }
        if (!manage && !canManage && teamLevel(org, role).compareTo(AccessLevel.VIEW) < 0) {
            throw new ForbiddenActionException("identity.team-read-denied", "Your role can't view the team");
        }
        return new TeamCaller(actor, userId, role, false);
    }

    private AccessLevel teamLevel(Organization org, String roleKey) {
        String module = policy.teamModuleKey(org.kind());
        return modules.matrix(policy.portalOf(org.kind())).stream()
                .filter(m -> m.moduleKey().equals(module) && m.roleKey().equals(roleKey))
                .map(ModuleAccess::level).findFirst().orElse(AccessLevel.NONE);
    }

    private static UUID uuidOrNull(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
