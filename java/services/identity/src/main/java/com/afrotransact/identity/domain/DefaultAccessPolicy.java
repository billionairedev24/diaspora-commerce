package com.afrotransact.identity.domain;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Default rules.
 * <ul>
 *   <li>Disabled accounts have no roles and no permissions.</li>
 *   <li>Effective permissions = permissions of every held role ∪ direct grants. Organisation roles count only
 *       while the organisation is approved; pending applicants act through their applicant role.</li>
 *   <li>{@code pt}: STAFF when holding a platform role other than driver; DRIVER when the only platform role is
 *       driver; SELLER_MEMBER / PROVIDER_MEMBER when acting for an organisation; otherwise CUSTOMER.</li>
 * </ul>
 */
public class DefaultAccessPolicy implements AccessPolicy {

    @Override
    public EffectiveAccess resolve(AccessProfile profile) {
        if (!profile.user().enabled()) {
            return new EffectiveAccess(PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of(), false);
        }
        Set<String> roles = new LinkedHashSet<>(profile.roleKeys());
        Set<String> perms = new HashSet<>(profile.directGrants());
        for (String key : profile.roleKeys()) {
            Role role = profile.roles().get(key);
            if (role != null) {
                perms.addAll(role.permissions());
            }
        }
        Optional<OrgMembership> active = activeMembership(profile);
        active.ifPresent(m -> {
            roles.add(m.roleKey());
            Role role = profile.roles().get(m.roleKey());
            if (role != null && m.approved()) {
                perms.addAll(role.permissions());
            }
        });
        boolean staff = profile.roleKeys().stream()
                .map(k -> profile.roles().get(k))
                .anyMatch(r -> r != null && r.portal() == Portal.PLATFORM && !RoleKeys.DRIVER.equals(r.key()));
        boolean driver = profile.holdsRole(RoleKeys.DRIVER);
        PrincipalType type;
        if (staff) {
            type = PrincipalType.STAFF;
        } else if (driver) {
            type = PrincipalType.DRIVER;
        } else if (active.isPresent()) {
            type = active.get().kind() == OrgKind.SELLER ? PrincipalType.SELLER_MEMBER : PrincipalType.PROVIDER_MEMBER;
        } else {
            type = PrincipalType.CUSTOMER;
        }
        return new EffectiveAccess(type, active.map(OrgMembership::orgId), roles, perms, staff);
    }

    @Override
    public Optional<OrgMembership> activeMembership(AccessProfile profile) {
        if (profile.memberships().isEmpty()) {
            return Optional.empty();
        }
        if (profile.user().activeOrgId() != null) {
            Optional<OrgMembership> selected = profile.memberships().stream()
                    .filter(m -> m.orgId().equals(profile.user().activeOrgId()))
                    .findFirst();
            if (selected.isPresent()) {
                return selected;
            }
        }
        return profile.memberships().stream()
                .min(Comparator.comparing((OrgMembership m) -> !m.approved())
                        .thenComparing(m -> m.kind().ordinal())
                        .thenComparing(OrgMembership::orgName));
    }
}
