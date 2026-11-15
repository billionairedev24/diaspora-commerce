package com.afrotransact.module.core;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;

/**
 * Inside an in-process module call the actor IS the calling module (a {@code SERVICE} actor whose permissions are the scopes it
 * was granted); the HTTP request that happens to be on the thread, its token and its {@code X-Acting-As} header play no part.
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
                .map(caller -> new Actor(caller.clientId(), PrincipalType.SERVICE, Optional.empty(), Set.of(),
                        caller.scopes()))
                .orElseGet(delegate::current);
    }
}
