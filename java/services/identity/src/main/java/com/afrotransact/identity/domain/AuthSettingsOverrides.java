package com.afrotransact.identity.domain;

/** The values an admin stored; null means "use the configured default". */
public record AuthSettingsOverrides(Integer passwordMinLength, Boolean mfaRequiredAccount, Boolean mfaRequiredSeller,
                                    Boolean mfaRequiredProvider, Integer sessionIdleTimeoutMinutes,
                                    Integer sessionAbsoluteTimeoutMinutes, Integer accessTokenTtlCapMinutes,
                                    Integer refreshTokenTtlCapMinutes) {

    public static AuthSettingsOverrides none() {
        return new AuthSettingsOverrides(null, null, null, null, null, null, null, null);
    }
}
