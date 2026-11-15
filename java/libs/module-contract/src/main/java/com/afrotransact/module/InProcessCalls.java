package com.afrotransact.module;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Binds the caller principal for the duration of one in-process call. The binding is a {@link ScopedValue}: it ends when the
 * call returns, it is not inherited by threads the callee starts, and it cannot leak to the next request on a reused thread.
 */
public final class InProcessCalls {

    private static final ScopedValue<CallerPrincipal> CALLER = ScopedValue.newInstance();

    private InProcessCalls() {
    }

    /** Runs {@code call} as {@code caller}; a nested call rebinds and restores the outer caller when it ends. */
    public static <T> T as(CallerPrincipal caller, Supplier<T> call) {
        return ScopedValue.where(CALLER, caller).call(call::get);
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
