package com.afrotransact.trustops.adapter.out.module;

import java.util.function.Supplier;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.trustops.port.UpstreamException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs one in-process call to another module as trust-ops' caller principal and gives its failures the shape the HTTP adapters
 * give theirs: a refusal the callee answers with a status keeps that status and its problem code as an
 * {@link UpstreamException}; anything else is status 0, "unavailable", as an unreachable service is over HTTP.
 */
@Component
public class ModuleUpstream {

    static final String MODULE = "trust-ops";

    private final ObjectProvider<CallerPrincipals> callers;

    ModuleUpstream(ObjectProvider<CallerPrincipals> callers) {
        this.callers = callers;
    }

    public <T> T call(String service, Supplier<T> call) {
        CallerPrincipal caller = callers.getObject().principalFor(MODULE);
        try {
            return InProcessCalls.as(caller, call);
        } catch (ModuleApiException e) {
            throw new UpstreamException(service, e.status(), e.code(), service + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new UpstreamException(service, 0, null, service + " unavailable: " + e.getMessage(), e);
        }
    }

    public void run(String service, Runnable call) {
        call(service, () -> {
            call.run();
            return null;
        });
    }
}
