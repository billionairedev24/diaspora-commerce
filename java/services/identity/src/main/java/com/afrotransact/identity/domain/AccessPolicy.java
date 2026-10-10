package com.afrotransact.identity.domain;

import java.util.Optional;

/** Computes effective access (principal type, org, roles, permissions) from an {@link AccessProfile}. */
public interface AccessPolicy {

    EffectiveAccess resolve(AccessProfile profile);

    /** The membership the user acts for: the selected active org, else the single (or first approved) one. */
    Optional<OrgMembership> activeMembership(AccessProfile profile);
}
