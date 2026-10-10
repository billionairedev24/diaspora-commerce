package com.afrotransact.platform.security;

/** Resolves the current {@link Actor} from the request's security context. */
public interface ActorResolver {

    Actor current();
}
