package com.afrotransact.identity.domain;

import java.util.Set;

/**
 * Whether a second factor is mandatory for a person: always for staff; for everyone else when their portal's switch
 * is on (Auth settings) and a factor can actually be asked of them (one is enrolled, or a phone is on file), so the
 * switch never locks someone out who has no way to answer.
 */
public final class MfaRequirement {

    private MfaRequirement() {
    }

    public static boolean mandatory(boolean staff, Set<String> roleKeys, AuthSettings settings, MfaEnrolment enrolment,
                                    boolean phoneOnFile) {
        if (staff) {
            return true;
        }
        boolean seller = roleKeys.stream().anyMatch(r -> r.startsWith("seller."));
        boolean provider = roleKeys.stream().anyMatch(r -> r.startsWith("provider."));
        boolean switchedOn = (seller && settings.mfaRequiredSeller()) || (provider && settings.mfaRequiredProvider())
                || (!seller && !provider && settings.mfaRequiredAccount());
        return switchedOn && (enrolment.anyFactor() || phoneOnFile);
    }
}
