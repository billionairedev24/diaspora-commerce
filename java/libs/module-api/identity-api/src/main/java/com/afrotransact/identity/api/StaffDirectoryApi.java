package com.afrotransact.identity.api;

import java.util.List;

/**
 * The staff roster seller reads over HTTP today ({@code GET /v1/internal/staff}, a service principal with {@code users:read}),
 * entered in-process under the calling module's principal. A caller without the scope is refused with
 * {@code ModuleApiException} status 403 ({@code identity.service-lookup-only}).
 */
public interface StaffDirectoryApi {

    /**
     * Enabled staff holding a permission or, when {@code role} is set, a role; or, with {@code ids}, just those enabled accounts.
     * Without ids, one of permission or role is required (status 422 otherwise).
     */
    List<StaffMember> staff(String permission, String role, List<String> ids);

    record StaffMember(String userId, String name, String email) {
    }
}
