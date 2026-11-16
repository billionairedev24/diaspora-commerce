package com.afrotransact.mcp.adapter.out.module;

import java.util.function.Function;

import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.Downstream;
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
 * Runs one in-process tool call to another module AS THE SIGNED-IN PERSON and gives its outcome the shape the HTTP adapters give theirs
 * ({@code JsonHttp}): a body, or 404 NOT_FOUND, 401 / 403 FORBIDDEN, any other 4xx REJECTED, and 5xx, a timeout, a full bulkhead or a
 * callee that crashed UNAVAILABLE. The person is the one captured at request entry ({@link CallerCredentials#person()}); there is no
 * way to run a tool call as a service identity, and a service token or a missing authentication is the callee's 403 / 401 here too.
 * The module itself still passes the bounded call gate under its own caller principal (timeout and bulkhead of the edge), whose
 * delegation scope only decides whether mcp-server may act for users on that API at all.
 */
@Component
class ModuleUpstream {

    static final String MODULE = "mcp-server";
    private static final Logger LOG = LoggerFactory.getLogger(ModuleUpstream.class);

    private final ObjectProvider<CallerPrincipals> callers;

    ModuleUpstream(ObjectProvider<CallerPrincipals> callers) {
        this.callers = callers;
    }

    /**
     * @param service the callee module as named in the module registry (and as the HTTP adapter's service name): selects the edge's
     *                timeout, bulkhead and metrics
     * @param call    the callee's API method, given the person
     */
    Downstream call(String service, CallerCredentials credentials, Function<EndUserActor, Object> call) {
        EndUserActor person;
        try {
            person = credentials.person().get();
        } catch (ModuleApiException refusal) {
            return Downstream.of(status(refusal.status()));
        }
        CallerPrincipal caller = callers.getObject().principalFor(MODULE);
        try {
            return Downstream.ok(InProcessCalls.as(caller, service, () -> call.apply(person)));
        } catch (ModuleApiException refusal) {
            if (refusal.status() >= 500) {
                LOG.warn("{} answered {}", service, refusal.status());
            }
            return Downstream.of(status(refusal.status()));
        } catch (RuntimeException e) {
            // The detail stays in the log, as the HTTP adapter's connection error does: a timeout, a full bulkhead or a crash is "unavailable".
            LOG.debug("{} unavailable: {}", service, e.getClass().getSimpleName());
            return Downstream.of(Downstream.Status.UNAVAILABLE);
        }
    }

    static Downstream.Status status(int code) {
        if (code == 404) {
            return Downstream.Status.NOT_FOUND;
        }
        if (code == 401 || code == 403) {
            return Downstream.Status.FORBIDDEN;
        }
        if (code >= 400 && code < 500) {
            return Downstream.Status.REJECTED;
        }
        return Downstream.Status.UNAVAILABLE;
    }
}
