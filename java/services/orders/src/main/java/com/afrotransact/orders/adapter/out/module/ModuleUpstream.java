package com.afrotransact.orders.adapter.out.module;

import java.util.List;
import java.util.function.Supplier;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Runs one in-process call to another module as orders' caller principal and gives its failures the shape the HTTP adapters
 * give theirs: a refusal the callee answers with a status (403, 404, 409, 422, ...) is an {@link UpstreamFailure} with that
 * status and code; anything else (the callee threw unexpectedly) is the retryable {@code <service>.unavailable}, as a timeout or
 * a 5xx is over HTTP.
 */
@Component
public class ModuleUpstream {

    static final String MODULE = "orders";
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
            throw new UpstreamFailure(service, e.status(), e.code(), e.getMessage(), e.retryable(), e.details(), e);
        } catch (RuntimeException e) {
            LOG.warn("In-process call to {} failed: {}", service, e.toString());
            throw new UpstreamFailure(service, 0, service + ".unavailable", service + " failed: " + e.getMessage(), true,
                    List.of(), e);
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
