package com.afrotransact.identity.domain;

/** Decides where a user lands after signing in. */
public interface LandingPolicy {

    Landing landing(AccessProfile profile, EffectiveAccess access);

    /** True when the email belongs to a staff domain; such addresses cannot self-register. */
    boolean isStaffEmail(String email);
}
