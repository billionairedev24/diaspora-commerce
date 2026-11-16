package com.afrotransact.module;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One consuming module's door to another module's published API: looks the API and the caller registry up on each call (the
 * modules reach each other in cycles, so neither may be resolved at construction) and runs the call as the consumer's caller
 * principal, the in-process equivalent of the consumer's client-credentials token.
 *
 * @param <A> the callee's published API interface
 */
public final class InProcessLink<A> {

    private final String caller;
    private final String callee;
    private final Supplier<? extends A> api;
    private final Supplier<? extends CallerPrincipals> callers;

    /** Temporary: a link that does not name its callee is bounded under {@link InProcessCalls#UNSPECIFIED}. */
    public InProcessLink(String caller, Supplier<? extends A> api, Supplier<? extends CallerPrincipals> callers) {
        this(caller, InProcessCalls.UNSPECIFIED, api, callers);
    }

    /** @param callee the called module as named in the module registry; it selects the timeout, bulkhead and metrics of the edge */
    public InProcessLink(String caller, String callee, Supplier<? extends A> api, Supplier<? extends CallerPrincipals> callers) {
        this.caller = caller;
        this.callee = callee;
        this.api = api;
        this.callers = callers;
    }

    /** Runs {@code call} against the callee's API as the consumer's principal. */
    public <T> T call(Function<A, T> call) {
        return call(null, call);
    }

    /** Same, under the edge's {@code <operation>-timeout} (for example the 60 s batch write of catalog to offers). */
    public <T> T call(String operation, Function<A, T> call) {
        CallerPrincipal principal = callers.get().principalFor(caller);
        return InProcessCalls.as(principal, callee, operation, () -> call.apply(api.get()));
    }

    /** Runs a call that returns nothing. */
    public void run(Consumer<A> call) {
        call(a -> {
            call.accept(a);
            return null;
        });
    }
}
