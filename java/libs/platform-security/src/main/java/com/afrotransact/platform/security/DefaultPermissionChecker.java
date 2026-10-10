package com.afrotransact.platform.security;

import com.afrotransact.platform.web.error.ForbiddenActionException;

/** Default {@link PermissionChecker} backed by an {@link ActorResolver}. */
public class DefaultPermissionChecker implements PermissionChecker {

    private final ActorResolver actors;

    public DefaultPermissionChecker(ActorResolver actors) {
        this.actors = actors;
    }

    @Override
    public Actor require(String permission) {
        Actor actor = actors.current();
        if (!actor.can(permission)) {
            throw new ForbiddenActionException("auth.missing-permission", "Missing permission " + permission);
        }
        return actor;
    }

    @Override
    public Actor requireForOrg(String permission, String orgId) {
        Actor actor = require(permission);
        boolean staff = actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
        if (!staff && !actor.orgId().map(orgId::equals).orElse(false)) {
            throw new ForbiddenActionException("auth.wrong-org", "Actor does not belong to organisation " + orgId);
        }
        return actor;
    }
}
