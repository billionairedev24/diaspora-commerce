package com.afrotransact.fulfilment.adapter.out.module;

import java.util.function.Supplier;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs one in-process call to another module as fulfilment's caller principal and gives its failures the shape the HTTP adapters
 * give theirs: a refusal the callee answers with a status (403, 404, 409, 422, ...) is an {@link UpstreamFailure} with that
 * status and code; anything else (the callee threw unexpectedly) is the retryable {@code <service>.unavailable}, as a timeout or
 * a 5xx is over HTTP.
 */
@Component
public class ModuleUpstream {

    static final String MODULE = "fulfilment";
    private static final Logger LOG = LoggerFactory.getLogger(ModuleUpstream.class);

    private final ObjectProvider<CallerPrincipals> callers;

    ModuleUpstream(ObjectProvider<CallerPrincipals> callers) {
        this.callers = callers;
    }

    /** @param service the callee's short name as the HTTP adapter reports it (inventory, payments, ...) */
    public <T> T call(String service, Supplier<T> call) {
        CallerPrincipal caller = callers.getObject().principalFor(MODULE);
        try {
            return InProcessCalls.as(caller, call);
        } catch (ModuleApiException e) {
            throw new UpstreamFailure(service, e.status(), e.code(), e.getMessage(), e.retryable(), e);
        } catch (RuntimeException e) {
            // The detail stays in the log: over HTTP a callee crash arrives as a bare 5xx, and this message is shown on.
            LOG.warn("In-process call to {} failed", service, e);
            throw new UpstreamFailure(service, 0, service + ".unavailable", service + " did not answer", true, e);
        }
    }

    /** {@link #call} for a call with no result. */
    public void run(String service, Runnable call) {
        call(service, () -> {
            call.run();
            return null;
        });
    }
}
