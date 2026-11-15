package com.afrotransact.identity.application;

import java.time.Duration;
import java.util.List;

/**
 * @param acceptUrl            staff console page that accepts an invitation; the token goes after {@code #token=} so it
 *                             never reaches a server log ({@code at.identity.staff-invitations.accept-url})
 * @param ttl                  how long an invitation (and each resend) stays valid, default 72 hours
 * @param tempPasswordEnabled  whether {@code POST /v1/admin/users} (account with a temporary password) still works;
 *                             off by default, invitations replace it
 * @param allowedEmailDomains  email domains staff can be invited with (empty: any)
 */
public record StaffInvitationSettings(String acceptUrl, Duration ttl, boolean tempPasswordEnabled,
                                      List<String> allowedEmailDomains) {

    public static final Duration DEFAULT_TTL = Duration.ofHours(72);

    public StaffInvitationSettings {
        acceptUrl = acceptUrl == null || acceptUrl.isBlank() ? "http://localhost:3002/invite" : acceptUrl;
        ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
        allowedEmailDomains = allowedEmailDomains == null ? List.of() : List.copyOf(allowedEmailDomains);
    }

    /** The link sent to the invitee. Base64url tokens need no escaping; the fragment is never sent to a server. */
    public String link(String token) {
        return acceptUrl + "#token=" + token;
    }
}
