package com.afrotransact.mcp.adapter.in.mcp;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.afrotransact.mcp.application.McpCaller;
import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.EndUsers;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * Captures the authenticated caller (subject, OAuth client, organisation, permissions), the bearer token and the signed-in person
 * ({@link EndUserActor}, for the in-process calls of afrotransact-core) on the request thread and puts them into the MCP transport
 * context, where the guard and the tools read them. The person is taken here, from the request's SecurityContext, because the tool
 * runs later, possibly on another thread, where the SecurityContext is gone; it is never built from anything else. A request with no
 * valid person (a service token) is captured as the refusal and only raised when an in-process call asks for it.
 */
public class McpCallerExtractor implements McpTransportContextExtractor<ServerRequest> {

    static final String CALLER = "afrotransact.caller";
    /** Local profile only (security off): lets tests and local agents simulate distinct OAuth clients. */
    static final String LOCAL_CLIENT_HEADER = "X-MCP-Client";

    private final ActorResolver actors;
    private final EndUsers endUsers;
    private final boolean securityEnabled;

    public McpCallerExtractor(ActorResolver actors, EndUsers endUsers, boolean securityEnabled) {
        this.actors = actors;
        this.endUsers = endUsers;
        this.securityEnabled = securityEnabled;
    }

    @Override
    public McpTransportContext extract(ServerRequest request) {
        Actor actor = actors.current();
        String clientId = securityEnabled ? clientId() : request.headers().firstHeader(LOCAL_CLIENT_HEADER);
        String header = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        CallerProfile profile = new CallerProfile(actor.subject(), clientId == null ? "local" : clientId,
                actor.type().name(), actor.orgId().orElse(null), actor.permissions());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(CALLER, new McpCaller(profile, token, capturePerson()));
        return McpTransportContext.create(metadata);
    }

    /** The person of this request, or the refusal to raise if an in-process call asks for one (taken now, on the request thread). */
    private Supplier<EndUserActor> capturePerson() {
        try {
            EndUserActor person = endUsers.current();
            return () -> person;
        } catch (ModuleApiException refusal) {
            return () -> {
                throw refusal;
            };
        }
    }

    /** The caller of a tool, from the transport context (throws when the request was not authenticated). */
    static McpCaller caller(McpTransportContext context) {
        Object caller = context == null ? null : context.get(CALLER);
        if (caller instanceof McpCaller c) {
            return c;
        }
        throw new IllegalStateException("MCP request without an authenticated caller");
    }

    private static String clientId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            for (String claim : new String[] {"client_id", "azp", "cid"}) {
                String v = jwt.getClaimAsString(claim);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }
}
