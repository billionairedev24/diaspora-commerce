package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.identity.application.ModuleMatrix;
import com.afrotransact.identity.application.ProviderAdminView;
import com.afrotransact.identity.application.UserAccessView;
import com.afrotransact.identity.domain.AccessChange;
import com.afrotransact.identity.domain.ModuleAccess;
import com.afrotransact.identity.domain.Permission;
import com.afrotransact.identity.domain.Role;
import com.afrotransact.identity.domain.UserAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Access control administration bodies. */
final class AdminDtos {

    private AdminDtos() {
    }

    @Schema(name = "Permission")
    record PermissionResponse(String key, String module, String description) {

        static PermissionResponse of(Permission p) {
            return new PermissionResponse(p.key(), p.module(), p.description());
        }
    }

    @Schema(name = "Role")
    record RoleResponse(String key, String portal, String name, String description, List<String> permissions) {

        static RoleResponse of(Role r) {
            return new RoleResponse(r.key(), r.portal().wire(), r.name(), r.description(),
                    List.copyOf(r.sortedPermissions()));
        }
    }

    @Schema(name = "UserAccess", description = "People tab row: roles, direct grants, effective access")
    record UserAccessResponse(UUID id, String name, String email, boolean enabled, boolean emailVerified,
                              String principalType, List<String> roles, List<String> directGrants,
                              List<MeDtos.OrgResponse> orgs, List<String> effectivePermissions,
                              boolean driverAppAccess) {

        static UserAccessResponse of(UserAccessView v) {
            UserAccount u = v.user();
            return new UserAccessResponse(u.id(), u.displayName(), u.email(), u.enabled(), u.emailVerified(),
                    v.effective().principalType().name(), List.copyOf(new TreeSet<>(v.roles())),
                    List.copyOf(new TreeSet<>(v.directGrants())),
                    v.memberships().stream().map(MeDtos.OrgResponse::of).toList(),
                    List.copyOf(new TreeSet<>(v.effective().permissions())), v.effective().can("delivery:operate"));
        }
    }

    @Schema(name = "UserPage")
    record UserPageResponse(List<UserAccessResponse> items, int page, int size, long total) {
    }

    @Schema(name = "CreateStaffUserRequest")
    record CreateStaffUserRequest(@NotBlank @Size(max = 80) String firstName, @NotBlank @Size(max = 80) String lastName,
                                  @NotBlank @Email String email, @NotBlank String phone, String phoneRegion,
                                  @NotBlank @Size(max = 128) String password, @NotNull List<String> roles) {
    }

    @Schema(name = "ReasonRequest")
    record ReasonRequest(@Size(max = 300) String reason) {
    }

    @Schema(name = "ModuleMatrix", description = "Rows: modules; columns: roles; cells: none/view/edit/approve/full")
    record ModuleMatrixResponse(String portal, List<ModuleRow> modules, List<RoleResponse> roles) {

        static ModuleMatrixResponse of(ModuleMatrix m) {
            Map<String, Map<String, String>> byModule = new TreeMap<>();
            for (ModuleAccess c : m.cells()) {
                byModule.computeIfAbsent(c.moduleKey(), k -> new TreeMap<>()).put(c.roleKey(), c.level().wire());
            }
            List<ModuleRow> rows = m.modules().stream()
                    .map(d -> new ModuleRow(d.key(), d.label(), byModule.getOrDefault(d.key(), Map.of())))
                    .toList();
            return new ModuleMatrixResponse(m.portal().wire(), rows, m.roles().stream().map(RoleResponse::of).toList());
        }
    }

    @Schema(name = "ModuleRow")
    record ModuleRow(String key, String label, Map<String, String> levels) {
    }

    @Schema(name = "SetModuleAccessRequest")
    record SetModuleAccessRequest(@NotBlank @Schema(allowableValues = {"none", "view", "edit", "approve", "full"})
                                  String level) {
    }

    @Schema(name = "ModuleAccess")
    record ModuleAccessResponse(String portal, String module, String role, String level) {

        static ModuleAccessResponse of(ModuleAccess a) {
            return new ModuleAccessResponse(a.portal().wire(), a.moduleKey(), a.roleKey(), a.level().wire());
        }
    }

    @Schema(name = "AccessChange")
    record AccessChangeResponse(long id, Instant at, String actorId, String action, UUID targetUserId,
                                String targetRole, String subject, String detail) {

        static AccessChangeResponse of(AccessChange c) {
            return new AccessChangeResponse(c.id(), c.at(), c.actorId(), c.action(), c.targetUserId(), c.targetRole(),
                    c.subject(), c.detail());
        }
    }

    @Schema(name = "AccessChangePage")
    record AccessChangePageResponse(List<AccessChangeResponse> items, int page, int size) {
    }

    @Schema(name = "IdentityProviderAdmin")
    record ProviderAdminResponse(String id, String displayName, boolean enabled, boolean configured,
                                 Instant updatedAt, String updatedBy) {

        static ProviderAdminResponse of(ProviderAdminView v) {
            return new ProviderAdminResponse(v.setting().id(), v.setting().displayName(), v.setting().enabled(),
                    v.configured(), v.setting().updatedAt(), v.setting().updatedBy());
        }
    }

    @Schema(name = "SetProviderEnabledRequest")
    record SetProviderEnabledRequest(@NotNull Boolean enabled) {
    }
}
