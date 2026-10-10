package com.afrotransact.identity.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessChange;
import com.afrotransact.identity.domain.AccessLevel;
import com.afrotransact.identity.domain.ModuleAccess;
import com.afrotransact.identity.domain.Permission;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;

/**
 * Access control administration (staff console › Access control). Viewing needs {@code access:read}
 * (or {@code access:admin}); changes need {@code access:admin}. Every change is written to the append-only
 * change log and published as an event; effective permissions are recomputed at the next token issuance.
 */
public interface AccessAdminService {

    List<Permission> permissions();

    List<Role> roles(Portal portal);

    /** Capabilities tab: add or remove a permission from a role. */
    Role setRolePermission(String roleKey, String permissionKey, boolean granted, String reason);

    PageView<UserAccessView> users(String query, int page, int size);

    UserAccessView user(UUID userId);

    UserAccessView createStaffUser(CreateStaffUserCommand command);

    /** People tab: toggle an account or platform role. */
    UserAccessView setRole(UUID userId, String roleKey, boolean granted, String reason);

    /** People tab: toggle a direct permission grant (for example {@code delivery:operate}). */
    UserAccessView setPermission(UUID userId, String permissionKey, boolean granted, String reason);

    /** Disabling also revokes every session and trusted browser so access ends immediately. */
    UserAccessView setEnabled(UUID userId, boolean enabled, String reason);

    ModuleMatrix moduleMatrix(Portal portal);

    ModuleAccess setModuleAccess(Portal portal, String moduleKey, String roleKey, AccessLevel level);

    PageView<AccessChange> changeLog(UUID userId, int page, int size);
}
