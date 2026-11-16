package com.afrotransact.module;

import java.util.function.Supplier;

/**
 * What stands between two modules in a monolith where the network used to be: a bounded wait (the old HTTP read timeout), a
 * bulkhead per called module (the old connection and thread limits of the other process) and the hand-over of the caller's
 * context to the thread that runs the callee. {@link InProcessCalls} sends every in-process call through the installed gate;
 * {@code module-core} installs the real one, a plain run on the caller's thread stays when none is installed (unit tests of one
 * module, standalone services, which use HTTP).
 */
public interface ModuleCallGate {

    /**
     * Runs {@code call} on a thread owned by the callee module and waits for it at most the edge's timeout.
     *
     * @param caller    the calling module
     * @param callee    the called module, as named in the module registry ({@code tax-returns}, {@code ai-gateway})
     * @param operation an optional finer name of the edge with its own timeout ({@code batch}); null for the edge itself
     * @param call      the callee call, already bound to the caller principal
     * @throws ModuleCallTimeoutException when the callee did not answer in time (the call is interrupted; it may still commit)
     * @throws ModuleApiException         status 503 {@code <callee>.unavailable} when the callee's bulkhead is full
     */
    <T> T execute(String caller, String callee, String operation, Supplier<T> call);
}
