package com.afrotransact.fulfilment.application;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Component;

/** Permission checks shared by the use cases (always in the application layer, never only in UIs). */
@Component
class FulfilmentAccess {

    private final PermissionChecker permissions;
    private final ActorResolver actors;

    FulfilmentAccess(PermissionChecker permissions, ActorResolver actors) {
        this.permissions = permissions;
        this.actors = actors;
    }

    Actor current() {
        return actors.current();
    }

    /** Fulfilment-centre operators (pick, pack, centres). */
    Actor operator() {
        return permissions.require(FulfilmentPermissions.OPERATE);
    }

    /** Dispatchers (routes, drivers, SOS, live stream). */
    Actor dispatcher() {
        return permissions.require(FulfilmentPermissions.DISPATCH);
    }

    /** Schedules / cancels return pickups: a SERVICE with delivery:returns (tax-returns) or a dispatcher. */
    Actor returnScheduler() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE && actor.can(FulfilmentPermissions.RETURNS)) {
            return actor;
        }
        return permissions.require(FulfilmentPermissions.DISPATCH);
    }

    /** Reads a return pickup: the scheduling service or dispatch. */
    Actor returnReader() {
        return returnScheduler();
    }

    /** Either operators or dispatchers (read-only views shared by both consoles). */
    Actor opsReader() {
        Actor actor = actors.current();
        if (!actor.can(FulfilmentPermissions.OPERATE) && !actor.can(FulfilmentPermissions.DISPATCH)) {
            throw new ForbiddenActionException("auth.missing-permission", "Missing permission "
                    + FulfilmentPermissions.OPERATE + " or " + FulfilmentPermissions.DISPATCH);
        }
        return actor;
    }
}
