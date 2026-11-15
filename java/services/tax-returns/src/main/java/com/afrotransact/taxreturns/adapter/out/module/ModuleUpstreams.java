package com.afrotransact.taxreturns.adapter.out.module;

import java.util.function.Function;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tax-returns' door to a module it calls in-process: the principal, the start check, and the failure mapping of the HTTP
 * {@code Upstream}: a status the callee answered is an {@link UpstreamException} with that status and problem code, no answer at
 * all is one with status 0.
 */
final class ModuleUpstreams<A> {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleUpstreams.class);

    private final InProcessLink<A> link;
    private final String name;

    ModuleUpstreams(Class<A> api, String callee, ObjectProvider<A> provider, ObjectProvider<CallerPrincipals> callers,
                    ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(api, true, false).length > 0, "tax-returns", callee,
                "at.taxreturns." + callee.replace("-", "") + ".mode");
        this.link = new InProcessLink<>("tax-returns", provider::getObject, callers::getObject);
        this.name = callee;
    }

    <T> T call(Function<A, T> call) {
        try {
            return link.call(call);
        } catch (ModuleApiException e) {
            throw new UpstreamException(name, e.status(), e.code(), name + ": " + e.getMessage(), e);
        } catch (UpstreamException e) {
            throw e;
        } catch (RuntimeException e) {
            // The detail goes to the log, not to the caller: over HTTP a callee crash arrives as a bare 5xx.
            LOG.warn("In-process call to {} failed", name, e);
            throw new UpstreamException(name, 0, null, name + " unavailable", e);
        }
    }
}
