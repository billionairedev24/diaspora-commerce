package com.afrotransact.module;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Binds the caller principal (and, for a call made for a signed-in person, the {@link EndUserActor}) for the duration of one
 * in-process call. The bindings are {@link ScopedValue}s: they end when the call returns, they are not inherited by threads the
 * callee starts, and they cannot leak to the next request on a reused thread.
 *
 * <p>Every call goes through the installed {@link ModuleCallGate}: the callee runs on a thread of its own module (no caller
 * transaction, security context or request attributes comes along), the caller waits at most the edge's timeout, and the
 * calling thread's MDC and trace context are handed over. Without a gate (unit tests, standalone services) the call runs on the
 * caller's thread.
 */
public final class InProcessCalls {

    /** Callee name of a call site that has not named its callee: bounded like any other, counted under this name. */
    public static final String UNSPECIFIED = "unspecified";

    private static final ScopedValue<CallerPrincipal> CALLER = ScopedValue.newInstance();
    private static final ScopedValue<EndUserActor> USER = ScopedValue.newInstance();

    private static volatile ModuleCallGate gate;

    private InProcessCalls() {
    }

    /** Installs the gate every call goes through (module-core does this once at start). */
    public static void install(ModuleCallGate installed) {
        gate = installed;
    }

    /** Removes {@code installed} if it is the gate in force (context shutdown, tests). */
    public static void uninstall(ModuleCallGate installed) {
        if (gate == installed) {
            gate = null;
        }
    }

    /** The gate in force, empty when calls run directly. */
    public static Optional<ModuleCallGate> gate() {
        return Optional.ofNullable(gate);
    }

    /** Runs {@code call} as {@code caller} against a callee not named by the call site; see {@link #UNSPECIFIED}. */
    public static <T> T as(CallerPrincipal caller, Supplier<T> call) {
        return as(caller, UNSPECIFIED, null, call);
    }

    /** Runs {@code call} as {@code caller} against module {@code callee}, within the edge's timeout and bulkhead. */
    public static <T> T as(CallerPrincipal caller, String callee, Supplier<T> call) {
        return as(caller, callee, null, call);
    }

    /**
     * @param operation a finer name of the edge with its own timeout ({@code at.<caller>.<callee>.<operation>-timeout}); null for the edge
     */
    public static <T> T as(CallerPrincipal caller, String callee, String operation, Supplier<T> call) {
        Supplier<T> bound = () -> ScopedValue.where(CALLER, caller).call(call::get);
        ModuleCallGate g = gate;
        return g == null ? bound.get() : g.execute(caller.module(), callee, operation, bound);
    }

    /**
     * The callee's side of a call made for a person: {@code body} runs with {@code user} as the person in force. Called by the
     * published API's entry guard, never by the calling module.
     */
    public static <T> T withUser(EndUserActor user, Supplier<T> body) {
        return ScopedValue.where(USER, user).call(body::get);
    }

    /** The person an in-process call is made for, empty when the call is made by a module for itself. */
    public static Optional<EndUserActor> currentUser() {
        return USER.isBound() ? Optional.of(USER.get()) : Optional.empty();
    }

    /** The caller of the current in-process call, empty outside one. */
    public static Optional<CallerPrincipal> current() {
        return CALLER.isBound() ? Optional.of(CALLER.get()) : Optional.empty();
    }

    /** The caller of the current in-process call; a provider that is entered without one fails closed. */
    public static CallerPrincipal require() {
        return current().orElseThrow(() -> new IllegalStateException(
                "In-process module API called without a caller principal: use InProcessCalls.as(principal, ...)"));
    }

    /**
     * Start-up check for an in-process adapter: the called module's API bean must exist in this JVM.
     *
     * @throws ModuleCallException naming both modules and the two ways out (enable the module or use http mode)
     */
    public static void requireRunning(boolean apiPresent, String caller, String callee, String modeProperty) {
        if (!apiPresent) {
            throw new ModuleCallException("Module '" + caller + "' is configured " + modeProperty + "=in-process but module '"
                    + callee + "' is not running in this JVM: enable it (at.modules." + callee + ".enabled=true) or set "
                    + modeProperty + "=http");
        }
    }
}
