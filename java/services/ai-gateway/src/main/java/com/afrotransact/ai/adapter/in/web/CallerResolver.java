package com.afrotransact.ai.adapter.in.web;

import com.afrotransact.ai.application.Caller;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.EndUsers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Builds the {@link Caller} from the authenticated actor and keeps the bearer token for relaying downstream. */
@Component
class CallerResolver {

    private final ActorResolver actors;
    private final EndUsers endUsers;

    CallerResolver(ActorResolver actors, EndUsers endUsers) {
        this.actors = actors;
        this.endUsers = endUsers;
    }

    /**
     * The caller of a Lola turn: as {@link #current}, plus the signed-in person acting as a shopper ({@code X-Acting-As: CUSTOMER}, which
     * the relayed token carries over HTTP). It must be called on the request thread, before the turn moves to Lola's worker thread, which
     * has no security context. A service token or no authentication gives no person (the in-process readers then answer "not available",
     * as an HTTP 401 does), never a service posing as one.
     */
    Caller shopper(HttpServletRequest request) {
        EndUserActor person;
        try {
            person = endUsers.currentAsCustomer();
        } catch (ModuleApiException notAPerson) {
            person = null;
        }
        return current(request).withUser(person);
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
