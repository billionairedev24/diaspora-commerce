package com.afrotransact.identity.domain;

import java.util.UUID;

/**
 * Narrows the access change log. Every field is optional and they combine with AND.
 *
 * @param userId     changes about this person
 * @param role       changes whose role is this key (role permissions, a person's role, module access of the role)
 * @param permission changes whose subject is this permission key
 */
public record AccessLogFilter(UUID userId, String role, String permission) {

    public static AccessLogFilter none() {
        return new AccessLogFilter(null, null, null);
    }

    public AccessLogFilter {
        role = blankToNull(role);
        permission = blankToNull(permission);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
