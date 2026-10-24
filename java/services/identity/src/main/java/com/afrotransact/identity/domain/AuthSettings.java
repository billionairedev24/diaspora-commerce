package com.afrotransact.identity.domain;

import java.time.Duration;

/**
 * The authentication settings in force: the stored overrides over the configured defaults.
 *
 * @param passwordMinLength        shortest accepted password (upper bound {@link PasswordPolicy#MAX_LENGTH})
 * @param mfaRequiredAccount       customers must use a second factor when they can (an enrolled factor or a phone)
 * @param mfaRequiredSeller        same for seller organisation members
 * @param mfaRequiredProvider      same for service-provider organisation members
 * @param sessionIdleTimeoutMinutes  sign-in (single sign-on) session ends after this long without a request
 * @param sessionAbsoluteTimeoutMinutes sign-in session ends this long after it started, however active
 * @param accessTokenTtlCapMinutes  ceiling on every client's access token lifetime (null = no ceiling)
 * @param refreshTokenTtlCapMinutes ceiling on every client's refresh token lifetime (null = no ceiling)
 */
public record AuthSettings(int passwordMinLength, boolean mfaRequiredAccount, boolean mfaRequiredSeller,
                           boolean mfaRequiredProvider, int sessionIdleTimeoutMinutes,
                           int sessionAbsoluteTimeoutMinutes, Integer accessTokenTtlCapMinutes,
                           Integer refreshTokenTtlCapMinutes) {

    /** What the stored overrides leave unset falls back to {@code defaults}. */
    public static AuthSettings resolve(AuthSettings defaults, AuthSettingsOverrides o) {
        return new AuthSettings(
                o.passwordMinLength() != null ? o.passwordMinLength() : defaults.passwordMinLength(),
                o.mfaRequiredAccount() != null ? o.mfaRequiredAccount() : defaults.mfaRequiredAccount(),
                o.mfaRequiredSeller() != null ? o.mfaRequiredSeller() : defaults.mfaRequiredSeller(),
                o.mfaRequiredProvider() != null ? o.mfaRequiredProvider() : defaults.mfaRequiredProvider(),
                o.sessionIdleTimeoutMinutes() != null ? o.sessionIdleTimeoutMinutes()
                        : defaults.sessionIdleTimeoutMinutes(),
                o.sessionAbsoluteTimeoutMinutes() != null ? o.sessionAbsoluteTimeoutMinutes()
                        : defaults.sessionAbsoluteTimeoutMinutes(),
                o.accessTokenTtlCapMinutes() != null ? o.accessTokenTtlCapMinutes() : defaults.accessTokenTtlCapMinutes(),
                o.refreshTokenTtlCapMinutes() != null ? o.refreshTokenTtlCapMinutes()
                        : defaults.refreshTokenTtlCapMinutes());
    }

    public Duration sessionIdleTimeout() {
        return Duration.ofMinutes(sessionIdleTimeoutMinutes);
    }

    public Duration sessionAbsoluteTimeout() {
        return Duration.ofMinutes(sessionAbsoluteTimeoutMinutes);
    }

    /** A client's access token lifetime under the ceiling. */
    public Duration capAccess(Duration clientTtl) {
        return cap(clientTtl, accessTokenTtlCapMinutes);
    }

    /** A client's refresh token lifetime under the ceiling. */
    public Duration capRefresh(Duration clientTtl) {
        return cap(clientTtl, refreshTokenTtlCapMinutes);
    }

    private static Duration cap(Duration ttl, Integer capMinutes) {
        if (capMinutes == null) {
            return ttl;
        }
        Duration cap = Duration.ofMinutes(capMinutes);
        return ttl.compareTo(cap) > 0 ? cap : ttl;
    }

    /** One line for the audit log. */
    public String auditSummary() {
        return "passwordMin=" + passwordMinLength + " mfa(account=" + mfaRequiredAccount + ",seller="
                + mfaRequiredSeller + ",provider=" + mfaRequiredProvider + ") idle=" + sessionIdleTimeoutMinutes
                + "m absolute=" + sessionAbsoluteTimeoutMinutes + "m accessCap=" + accessTokenTtlCapMinutes
                + "m refreshCap=" + refreshTokenTtlCapMinutes + "m";
    }
}
