package com.afrotransact.identity.domain;

import java.util.Set;
import java.util.TreeSet;

/** A named bundle of permissions for one portal. */
public record Role(String key, Portal portal, String name, String description, Set<String> permissions) {

    public Role {
        permissions = Set.copyOf(permissions);
    }

    public Set<String> sortedPermissions() {
        return new TreeSet<>(permissions);
    }
}
