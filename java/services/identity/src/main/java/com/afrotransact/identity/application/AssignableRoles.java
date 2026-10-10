package com.afrotransact.identity.application;

import java.util.List;

import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.Role;

/** Roles that can be granted or invited in an organisation (S245): seller, provider or both role sets. */
public record AssignableRoles(String orgId, OrgKind kind, List<Role> roles) {

    public AssignableRoles {
        roles = List.copyOf(roles);
    }
}
