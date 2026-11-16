package com.afrotransact.ai.adapter.out.module;

import java.util.function.Function;
import java.util.function.Supplier;

import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs one in-process read of Lola's tools as ai-gateway's caller principal and gives its failures the shape the HTTP readers give
 * theirs: Lola's readers never throw. A refusal the callee answers with a status (404, 403, 422, the bulkhead's 503 ...) is handed to the
 * reader to map as it maps the HTTP status; anything else (the callee crashed, the bounded wait ran out) is "not available", as a timeout
 * or a 5xx is over HTTP. The callee's own message is never passed on: over HTTP a callee crash arrives as a bare 5xx.
 *
 * <p>Reads made for the shopper take the person captured on the request thread ({@link Shopper#user()}): Lola's worker thread has no
 * security context, so nothing is read from the thread here. Without a person (a service token, no authentication) the callee is not
 * called and the read is "not available", as the HTTP 401 is.
 */
@Component
class ModuleUpstream {

    static final String MODULE = "ai-gateway";
    private static final Logger LOG = LoggerFactory.getLogger(ModuleUpstream.class);

    private final ObjectProvider<CallerPrincipals> callers;

    ModuleUpstream(ObjectProvider<CallerPrincipals> callers) {
        this.callers = callers;
    }

    /**
     * @param callee      the called module's name (the edge's timeout, bulkhead and metrics are by it)
     * @param refused     maps a status the callee answered
     * @param unavailable the answer when the callee did not answer
     */
    <T> T call(String callee, Supplier<T> call, Function<ModuleApiException, T> refused, Supplier<T> unavailable) {
        CallerPrincipal caller = callers.getObject().principalFor(MODULE);
        try {
            return InProcessCalls.as(caller, callee, call);
        } catch (ModuleApiException e) {
            return refused.apply(e);
        } catch (RuntimeException e) {
            LOG.warn("In-process read of {} failed: {}", callee, e.getClass().getSimpleName());
            return unavailable.get();
        }
    }

    /** {@link #call} for the shopper of the request; no person is "not available". */
    <T> T forShopper(String callee, Shopper shopper, Function<EndUserActor, T> call, Function<ModuleApiException, T> refused,
                     Supplier<T> unavailable) {
        EndUserActor user = shopper == null ? null : shopper.user();
        if (user == null) {
            LOG.debug("No signed-in person for the read of {}", callee);
            return unavailable.get();
        }
        return call(callee, () -> call.apply(user), refused, unavailable);
    }
}
