package com.afrotransact.platform.security;

import java.util.Optional;
import java.util.Set;

/** Local-profile resolver used when {@code at.security.enabled=false}: a staff super admin with every permission. */
public class LocalActorResolver implements ActorResolver {

    @Override
    public Actor current() {
        return new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
                Set.of("platform.super_admin"), Set.of("*"));
    }
}
