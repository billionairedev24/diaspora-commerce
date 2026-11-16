package com.afrotransact.identity.application;

import java.util.UUID;

import com.afrotransact.identity.port.ServiceTokenVerifier;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves the signed-in user's id from the access token subject (user tokens carry the account UUID). */
@Component
class CurrentUser {

    private final ActorResolver actors;
    private final ServiceTokenVerifier services;

    CurrentUser(ActorResolver actors) {
        this(actors, null);
    }

    @Autowired
    CurrentUser(ActorResolver actors, ServiceTokenVerifier services) {
        this.actors = actors;
        this.services = services;
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

    /**
     * The caller of a service-to-service endpoint: the resolved actor, or, when that is not a service (core on the local
     * profile resolves every call to the local super admin), the verified client-credentials token of the request.
     */
    Actor caller() {
        Actor actor = actors.current();
        if (actor.type() == PrincipalType.SERVICE || services == null) {
            return actor;
        }
        return services.verifiedService().orElse(actor);
    }
}
