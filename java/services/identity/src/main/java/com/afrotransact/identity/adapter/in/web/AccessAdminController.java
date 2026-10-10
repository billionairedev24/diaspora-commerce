package com.afrotransact.identity.adapter.in.web;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.web.AdminDtos.AccessChangePageResponse;
import com.afrotransact.identity.adapter.in.web.AdminDtos.AccessChangeResponse;
import com.afrotransact.identity.adapter.in.web.AdminDtos.CreateStaffUserRequest;
import com.afrotransact.identity.adapter.in.web.AdminDtos.ModuleAccessResponse;
import com.afrotransact.identity.adapter.in.web.AdminDtos.ModuleMatrixResponse;
import com.afrotransact.identity.adapter.in.web.AdminDtos.PermissionResponse;
import com.afrotransact.identity.adapter.in.web.AdminDtos.ReasonRequest;
import com.afrotransact.identity.adapter.in.web.AdminDtos.RoleResponse;
import com.afrotransact.identity.adapter.in.web.AdminDtos.SetModuleAccessRequest;
import com.afrotransact.identity.adapter.in.web.AdminDtos.UserAccessResponse;
import com.afrotransact.identity.adapter.in.web.AdminDtos.UserPageResponse;
import com.afrotransact.identity.application.AccessAdminService;
import com.afrotransact.identity.application.CreateStaffUserCommand;
import com.afrotransact.identity.application.PageView;
import com.afrotransact.identity.application.UserAccessView;
import com.afrotransact.identity.domain.AccessChange;
import com.afrotransact.identity.domain.AccessLevel;
import com.afrotransact.identity.domain.Portal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Access control", description = "Module matrix, capabilities, people and change log "
        + "(access:read to view, access:admin to change)")
@RestController
class AccessAdminController {

    private final AccessAdminService admin;

    AccessAdminController(AccessAdminService admin) {
        this.admin = admin;
    }

    @Operation(summary = "Permission catalogue")
    @GetMapping("/v1/admin/permissions")
    List<PermissionResponse> permissions() {
        return admin.permissions().stream().map(PermissionResponse::of).toList();
    }

    @Operation(summary = "Roles of a portal with their permissions (capabilities tab)")
    @GetMapping("/v1/admin/roles")
    List<RoleResponse> roles(@RequestParam(defaultValue = "platform") String portal) {
        return admin.roles(portal(portal)).stream().map(RoleResponse::of).toList();
    }

    @Operation(summary = "Add a permission to a role")
    @PutMapping("/v1/admin/roles/{roleKey}/permissions/{permissionKey}")
    RoleResponse addRolePermission(@PathVariable String roleKey, @PathVariable String permissionKey,
                                   @RequestParam(required = false) String reason) {
        return RoleResponse.of(admin.setRolePermission(roleKey, permissionKey, true, reason));
    }

    @Operation(summary = "Remove a permission from a role (holders lose it on their next token)")
    @DeleteMapping("/v1/admin/roles/{roleKey}/permissions/{permissionKey}")
    RoleResponse removeRolePermission(@PathVariable String roleKey, @PathVariable String permissionKey,
                                      @RequestParam(required = false) String reason) {
        return RoleResponse.of(admin.setRolePermission(roleKey, permissionKey, false, reason));
    }

    @Operation(summary = "People: search users by name or email")
    @GetMapping("/v1/admin/users")
    UserPageResponse users(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "50") int size) {
        PageView<UserAccessView> p = admin.users(q, page, size);
        return new UserPageResponse(p.items().stream().map(UserAccessResponse::of).toList(), p.page(), p.size(),
                p.total());
    }

    @Operation(summary = "Provision a staff or driver account with initial roles")
    @PostMapping("/v1/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserAccessResponse create(@Valid @RequestBody CreateStaffUserRequest body) {
        return UserAccessResponse.of(admin.createStaffUser(new CreateStaffUserCommand(body.firstName(),
                body.lastName(), body.email(), body.phone(), body.phoneRegion(), body.password(), body.roles())));
    }

    @Operation(summary = "A user's roles, direct grants and effective permissions")
    @GetMapping("/v1/admin/users/{userId}")
    UserAccessResponse user(@PathVariable UUID userId) {
        return UserAccessResponse.of(admin.user(userId));
    }

    @Operation(summary = "Assign an account or platform role",
            description = "S248: market (ISO 3166-1 alpha-2, e.g. GB) scopes a staff role to one market; repeat for more "
                    + "markets. Without market the role is platform-wide. A role is either platform-wide or "
                    + "market-scoped (409 identity.role-platform-wide / identity.role-market-scoped). Super Admin and "
                    + "account roles are never market-scoped (422 identity.role-not-market-scoped). "
                    + "identity.role.granted carries the market for authz-sync.")
    @PutMapping("/v1/admin/users/{userId}/roles/{roleKey}")
    UserAccessResponse assignRole(@PathVariable UUID userId, @PathVariable String roleKey,
                                  @RequestParam(required = false) String reason,
                                  @RequestParam(required = false) String market) {
        return UserAccessResponse.of(admin.setRole(userId, roleKey, true, reason, market));
    }

    @Operation(summary = "Remove a role (takes effect on the user's next token)",
            description = "With market, removes only that market's scope (the role goes when its last market does); "
                    + "without market, removes the role with every market.")
    @DeleteMapping("/v1/admin/users/{userId}/roles/{roleKey}")
    UserAccessResponse removeRole(@PathVariable UUID userId, @PathVariable String roleKey,
                                  @RequestParam(required = false) String reason,
                                  @RequestParam(required = false) String market) {
        return UserAccessResponse.of(admin.setRole(userId, roleKey, false, reason, market));
    }

    @Operation(summary = "Grant a permission directly (for example delivery:operate)")
    @PutMapping("/v1/admin/users/{userId}/permissions/{permissionKey}")
    UserAccessResponse grant(@PathVariable UUID userId, @PathVariable String permissionKey,
                             @RequestParam(required = false) String reason) {
        return UserAccessResponse.of(admin.setPermission(userId, permissionKey, true, reason));
    }

    @Operation(summary = "Revoke a direct permission grant (takes effect on the user's next token)")
    @DeleteMapping("/v1/admin/users/{userId}/permissions/{permissionKey}")
    UserAccessResponse revoke(@PathVariable UUID userId, @PathVariable String permissionKey,
                              @RequestParam(required = false) String reason) {
        return UserAccessResponse.of(admin.setPermission(userId, permissionKey, false, reason));
    }

    @Operation(summary = "Disable an account; all its sessions end immediately")
    @PostMapping("/v1/admin/users/{userId}/disable")
    UserAccessResponse disable(@PathVariable UUID userId, @Valid @RequestBody(required = false) ReasonRequest body) {
        return UserAccessResponse.of(admin.setEnabled(userId, false, body == null ? null : body.reason()));
    }

    @Operation(summary = "Re-enable an account")
    @PostMapping("/v1/admin/users/{userId}/enable")
    UserAccessResponse enable(@PathVariable UUID userId, @Valid @RequestBody(required = false) ReasonRequest body) {
        return UserAccessResponse.of(admin.setEnabled(userId, true, body == null ? null : body.reason()));
    }

    @Operation(summary = "Module access matrix of a portal")
    @GetMapping("/v1/admin/module-access")
    ModuleMatrixResponse matrix(@RequestParam(defaultValue = "platform") String portal) {
        return ModuleMatrixResponse.of(admin.moduleMatrix(portal(portal)));
    }

    @Operation(summary = "Set one cell of the module access matrix")
    @PutMapping("/v1/admin/module-access/{portal}/{moduleKey}/{roleKey}")
    ModuleAccessResponse setModuleAccess(@PathVariable String portal, @PathVariable String moduleKey,
                                         @PathVariable String roleKey, @Valid @RequestBody SetModuleAccessRequest body) {
        AccessLevel level;
        try {
            level = AccessLevel.parse(body.level());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("level must be none, view, edit, approve or full", ex);
        }
        return ModuleAccessResponse.of(admin.setModuleAccess(portal(portal), moduleKey, roleKey, level));
    }

    @Operation(summary = "Append-only access change log (who changed what and when)")
    @GetMapping("/v1/admin/access-log")
    AccessChangePageResponse log(@RequestParam(required = false) UUID userId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        PageView<AccessChange> p = admin.changeLog(userId, page, size);
        return new AccessChangePageResponse(p.items().stream().map(AccessChangeResponse::of).toList(), p.page(),
                p.size());
    }

    private static Portal portal(String value) {
        try {
            return Portal.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("portal must be account, platform, seller or provider", ex);
        }
    }
}
