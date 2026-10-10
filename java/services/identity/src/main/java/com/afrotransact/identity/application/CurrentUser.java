package com.afrotransact.identity.application;

import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Component;

/** Resolves the signed-in user's id from the access token subject (user tokens carry the account UUID). */
@Component
class CurrentUser {

    private final ActorResolver actors;

    CurrentUser(ActorResolver actors) {
        this.actors = actors;
    }

    UUID id() {
        Actor actor = actors.current();
        try {
            return UUID.fromString(actor.subject());
        } catch (IllegalArgumentException ex) {
            throw new ForbiddenActionException("identity.user-token-required",
                    "This endpoint needs a user access token, not a client token");
        }
    }

    Actor actor() {
        return actors.current();
    }
}
