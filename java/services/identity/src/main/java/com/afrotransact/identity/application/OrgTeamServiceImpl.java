package com.afrotransact.identity.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.application.OrgTeamAccess.TeamCaller;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgMember;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.TeamPolicy;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgTeamServiceImpl implements OrgTeamService {

    private final OrgTeamAccess access;
    private final OrganizationRepository orgs;
    private final RoleRepository roles;
    private final UserAccountRepository users;
    private final MembershipWriter writer;
    private final TeamPolicy policy;

    OrgTeamServiceImpl(OrgTeamAccess access, OrganizationRepository orgs, RoleRepository roles,
                       UserAccountRepository users, MembershipWriter writer, TeamPolicy policy) {
        this.access = access;
        this.orgs = orgs;
        this.roles = roles;
        this.users = users;
        this.writer = writer;
        this.policy = policy;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgMember> members(String orgId) {
        Organization org = access.org(orgId);
        access.requireRead(org);
        return orgs.members(org.id());
    }

    @Override
    @Transactional(readOnly = true)
    public AssignableRoles assignableRoles(String orgId) {
        Organization org = access.org(orgId);
        access.requireRead(org);
        List<Portal> portals = org.kind() == OrgKind.BOTH ? List.of(Portal.SELLER, Portal.PROVIDER)
                : List.of(policy.portalOf(org.kind()));
        List<Role> result = new ArrayList<>();
        for (Portal portal : portals) {
            result.addAll(roles.roles(portal));
        }
        return new AssignableRoles(org.id(), org.kind(), result);
    }

    @Override
    @Transactional
    public void setMember(String orgId, UUID userId, String roleKey) {
        Organization org = access.org(orgId);
        TeamCaller caller = access.requireManage(org);
        Role role = roles.role(roleKey).orElseThrow(() -> new IdentityRuleViolation("identity.invalid-role",
                "Unknown role " + roleKey, Map.of("roleKey", "Choose a role of this organisation")));
        policy.requireRoleFor(org.kind(), role);
        String currentRole = orgs.memberRole(org.id(), userId).orElse(null);
        if (currentRole == null && !caller.privileged()) {
            throw new NotFoundException("identity.member-not-found",
                    "Not a member of this organisation; send an invitation instead");
        }
        UserAccount user = users.findById(userId).orElseThrow(() -> new NotFoundException(
                "identity.account-not-found", "Account not found"));
        if (!caller.privileged() && userId.equals(caller.userId())) {
            throw new ForbiddenActionException("identity.own-role", "You can't change your own role");
        }
        access.requireOwnerChangeAllowed(caller, currentRole, roleKey);
        if (currentRole != null && !caller.service()) {
            policy.requireOwnerRemains(currentRole, roleKey, otherOwners(org.id(), userId));
        }
        writer.set(org, user, currentRole, roleKey, caller.subject(), reason(caller));
    }

    @Override
    @Transactional
    public void removeMember(String orgId, UUID userId) {
        Organization org = access.org(orgId);
        TeamCaller caller = access.requireManage(org);
        String currentRole = orgs.memberRole(org.id(), userId).orElse(null);
        if (currentRole == null) {
            if (caller.service()) {
                return;
            }
            throw new NotFoundException("identity.member-not-found", "Not a member of this organisation");
        }
        access.requireOwnerChangeAllowed(caller, currentRole);
        if (!caller.service()) {
            policy.requireOwnerRemains(currentRole, null, otherOwners(org.id(), userId));
        }
        writer.remove(org, userId, currentRole, caller.subject(), reason(caller));
    }

    private int otherOwners(String orgId, UUID userId) {
        return (int) orgs.ownerUserIds(orgId).stream().filter(id -> !id.equals(userId)).count();
    }

    private static String reason(TeamCaller caller) {
        return caller.service() ? "org membership" : "team management";
    }
}
