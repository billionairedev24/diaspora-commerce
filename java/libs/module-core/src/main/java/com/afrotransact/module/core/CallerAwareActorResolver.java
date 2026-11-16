package com.afrotransact.module.core;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;

/**
 * Inside an in-process module call the actor IS the calling module (a {@code SERVICE} actor whose permissions are the scopes it
 * was granted); the HTTP request that happens to be on the thread, its token and its {@code X-Acting-As} header play no part.
 * The exception is a call made for a signed-in person ({@link EndUserActor}, entered through {@code ModuleEntry.runAsUser}):
 * the actor is that person, with the token's own type, organisation, roles and permissions, and never a {@code SERVICE}.
 * Outside such a call the normal resolver applies, unchanged.
 */
final class CallerAwareActorResolver implements ActorResolver {

    private final ActorResolver delegate;

    CallerAwareActorResolver(ActorResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Actor current() {
        return InProcessCalls.current()
                .map(caller -> InProcessCalls.currentUser().map(CallerAwareActorResolver::person)
                        .orElseGet(() -> new Actor(caller.clientId(), PrincipalType.SERVICE, Optional.empty(), Set.of(),
                                caller.scopes())))
                .orElseGet(delegate::current);
    }

    /** The person an in-process call is made for: their token's subject, type, organisation, roles and permissions, nothing of the caller's. */
    static Actor person(EndUserActor user) {
        PrincipalType type = PrincipalType.valueOf(user.principalType());
        if (type == PrincipalType.SERVICE) {
            throw new IllegalStateException("An end-user actor cannot be a SERVICE");
        }
        return new Actor(user.userId(), type, user.organisationId(), user.roles(), user.scopes());
    }
}
