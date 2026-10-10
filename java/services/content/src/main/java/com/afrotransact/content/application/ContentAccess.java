package com.afrotransact.content.application;

import java.util.Optional;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Component;

/** Permission checks of content use cases. */
@Component
public class ContentAccess {

    private final PermissionChecker permissions;
    private final ActorResolver actors;

    ContentAccess(PermissionChecker permissions, ActorResolver actors) {
        this.permissions = permissions;
        this.actors = actors;
    }

    public Actor require(String permission) {
        return permissions.require(permission);
    }

    /** Requires at least one of the permissions (edit implies view). */
    public Actor requireAny(String... anyOf) {
        Actor actor = actors.current();
        for (String p : anyOf) {
            if (actor.can(p)) {
                return actor;
            }
        }
        throw new ForbiddenActionException("auth.missing-permission", "Missing permission " + anyOf[0]);
    }

    /** The caller when authenticated, empty for anonymous visitors. */
    public Optional<Actor> optional() {
        try {
            return Optional.of(actors.current());
        } catch (ForbiddenActionException ex) {
            return Optional.empty();
        }
    }
}
