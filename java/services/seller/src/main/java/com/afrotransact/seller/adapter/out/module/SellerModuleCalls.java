package com.afrotransact.seller.adapter.out.module;

import java.util.function.Function;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.seller.port.GatewayException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Seller's door to the modules it calls in-process: the principal, the start check, and the failure mapping the HTTP adapters
 * apply (a status the callee answered, or no answer at all) as {@link GatewayException}s with the same codes.
 */
final class SellerModuleCalls<A> {

    private static final String MODULE = "seller";

    private final InProcessLink<A> link;
    private final String callee;

    SellerModuleCalls(Class<A> api, String callee, ObjectProvider<A> provider, ObjectProvider<CallerPrincipals> callers,
                      ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(api, true, false).length > 0, MODULE, callee,
                "at.seller." + callee.replace("-", "") + ".mode");
        this.link = new InProcessLink<>(MODULE, provider::getObject, callers::getObject);
        this.callee = callee;
    }

    /** Runs the call; a callee refusal or failure becomes the HTTP adapter's {@code <prefix>-failed} / {@code -unavailable}. */
    <T> T call(Function<A, T> call, String codePrefix) {
        try {
            return link.call(call);
        } catch (ModuleApiException e) {
            throw failed(codePrefix, e);
        } catch (RuntimeException e) {
            throw new GatewayException(codePrefix + "-unavailable", callee + " unavailable: " + e.getMessage(), false, e);
        }
    }

    /** The call and the interpretation of its refusal are the caller's: only the principal and the link are shared. */
    <T> T raw(Function<A, T> call) {
        return link.call(call);
    }

    GatewayException failed(String codePrefix, ModuleApiException e) {
        return new GatewayException(codePrefix + "-failed", callee + " answered " + e.status(),
                e.status() >= 400 && e.status() < 500, e);
    }

    GatewayException unavailable(String codePrefix, RuntimeException e) {
        return new GatewayException(codePrefix + "-unavailable", callee + " unavailable: " + e.getMessage(), false, e);
    }
}
