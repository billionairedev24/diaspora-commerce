package com.afrotransact.ai.adapter.in.web;

import com.afrotransact.ai.application.Caller;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Builds the {@link Caller} from the authenticated actor and keeps the bearer token for relaying downstream. */
@Component
class CallerResolver {

    private final ActorResolver actors;

    CallerResolver(ActorResolver actors) {
        this.actors = actors;
    }

    Caller current(HttpServletRequest request) {
        Actor actor = actors.current();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        return new Caller(actor.subject(), actor.type().name(), actor.orgId().orElse(null), actor.permissions(),
                token);
    }
}
