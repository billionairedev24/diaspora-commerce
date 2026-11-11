package com.afrotransact.identity.domain;

import java.util.Set;

/**
 * Whether two-step verification is MANDATORY for a person: always for staff; for everyone else when their portal's
 * switch is on (Auth settings: sellers, providers, other accounts). Mandatory never means "send a code": a person
 * with no enrolled factor must first enrol (authenticator app or email codes) right after their password, and only
 * then gets a session. Codes are only ever asked of people who enrolled a factor.
 */
public final class MfaRequirement {

    private MfaRequirement() {
    }

    public static boolean mandatory(boolean staff, Set<String> roleKeys, AuthSettings settings) {
        if (staff) {
            return true;
        }
        boolean seller = roleKeys.stream().anyMatch(r -> r.startsWith("seller."));
        boolean provider = roleKeys.stream().anyMatch(r -> r.startsWith("provider."));
        return (seller && settings.mfaRequiredSeller()) || (provider && settings.mfaRequiredProvider())
                || (!seller && !provider && settings.mfaRequiredAccount());
    }
}
