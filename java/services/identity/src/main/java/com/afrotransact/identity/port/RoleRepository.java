package com.afrotransact.identity.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.Permission;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;

/** The permission catalogue and role definitions. */
public interface RoleRepository {

    List<Permission> permissions();

    boolean permissionExists(String key);

    List<Role> roles(Portal portal);

    Optional<Role> role(String key);

    Map<String, Role> roles(Set<String> keys);

    void addPermissionToRole(String roleKey, String permissionKey);

    void removePermissionFromRole(String roleKey, String permissionKey);

    /** Users holding the role directly (platform/account roles). */
    List<UUID> holders(String roleKey);
}
