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
    private final Supplier<? extends A> api;
    private final Supplier<? extends CallerPrincipals> callers;

    public InProcessLink(String caller, Supplier<? extends A> api, Supplier<? extends CallerPrincipals> callers) {
        this.caller = caller;
        this.api = api;
        this.callers = callers;
    }

    /** Runs {@code call} against the callee's API as the consumer's principal. */
    public <T> T call(Function<A, T> call) {
        CallerPrincipal principal = callers.get().principalFor(caller);
        return InProcessCalls.as(principal, () -> call.apply(api.get()));
    }

    /** Runs a call that returns nothing. */
    public void run(Consumer<A> call) {
        call(a -> {
            call.accept(a);
            return null;
        });
    }
}
