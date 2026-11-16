package com.afrotransact.mcp.application;

import java.util.function.Supplier;

import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.ModuleApiException;

/**
 * The caller of a tool, the access token relayed to platform APIs over HTTP and the signed-in person the in-process adapters act as.
 * All three are captured on the request thread when the MCP request enters (the tool may run elsewhere).
 *
 * @param profile     who is calling
 * @param bearerToken incoming access token (null in the local profile)
 * @param person      the signed-in person as an end-user actor; {@code get()} throws {@link ModuleApiException} (401 / 403) when the
 *                    request carried no valid person
 */
public record McpCaller(CallerProfile profile, String bearerToken, Supplier<EndUserActor> person) {

    /** A caller whose person was not captured (HTTP adapters only need the token): asking for the person is a 401. */
    public McpCaller(CallerProfile profile, String bearerToken) {
        this(profile, bearerToken, () -> {
            throw new ModuleApiException(401, "auth.unauthenticated", "No authenticated user");
        });
    }

    /** What the platform API ports take: the token for HTTP, the person for in-process calls. */
    public CallerCredentials credentials() {
        return new CallerCredentials(bearerToken, person);
    }

    @Override
    public String toString() {
        return "McpCaller[" + profile.subject() + "@" + profile.clientId() + ", token=" + (bearerToken == null ? "none"
                : "***") + "]";
    }
}
