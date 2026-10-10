package com.afrotransact.identity.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.afrotransact.identity.domain.AccessProfile;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.RoleRepository;
import com.afrotransact.identity.port.UserAccessRepository;
import org.springframework.stereotype.Component;

/** Assembles an {@link AccessProfile} from the RBAC tables. */
@Component
class AccessProfileLoader {

    private final UserAccessRepository access;
    private final RoleRepository roles;
    private final OrganizationRepository orgs;

    AccessProfileLoader(UserAccessRepository access, RoleRepository roles, OrganizationRepository orgs) {
        this.access = access;
        this.roles = roles;
        this.orgs = orgs;
    }

    AccessProfile load(UserAccount user) {
        Set<String> roleKeys = access.roleKeys(user.id());
        List<OrgMembership> memberships = orgs.memberships(user.id());
        Set<String> all = new HashSet<>(roleKeys);
        memberships.forEach(m -> all.add(m.roleKey()));
        return new AccessProfile(user, roleKeys, access.directGrants(user.id()), memberships, roles.roles(all));
    }
}
