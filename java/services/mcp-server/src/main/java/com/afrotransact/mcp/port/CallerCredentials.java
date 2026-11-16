package com.afrotransact.mcp.port;

import java.util.function.Supplier;

import com.afrotransact.module.EndUserActor;

/**
 * What a tool call acts with towards the platform APIs, both captured at request entry (the tool itself may run on another thread, where
 * the request's security context is gone):
 *
 * <ul>
 *   <li>{@code bearerToken}: the caller's access token, relayed by the HTTP adapters (microservices profile); null in the local profile;</li>
 *   <li>{@code person}: the signed-in person as an {@link EndUserActor}, used by the in-process adapters (afrotransact-core): the callee
 *       runs AS that person, never as a service and never with more than the token grants. Asking for it fails with
 *       {@code ModuleApiException} 401 / 403 when the request carried no valid person (no authentication, or a service token).</li>
 * </ul>
 *
 * @param bearerToken incoming access token (null in the local profile)
 * @param person      the person captured on the request thread; {@code get()} throws when there was none
 */
public record CallerCredentials(String bearerToken, Supplier<EndUserActor> person) {

    @Override
    public String toString() {
        return "CallerCredentials[token=" + (bearerToken == null ? "none" : "***") + "]";
    }
}
