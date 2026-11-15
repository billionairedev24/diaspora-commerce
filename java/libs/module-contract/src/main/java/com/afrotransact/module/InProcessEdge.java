package com.afrotransact.module;

/**
 * One in-process edge of the module graph, declared as a bean by the consuming module when {@code at.<caller>.mode=in-process}:
 * the caller reaches the callee through the callee's published API. Declaring it runs the start-up check (see
 * {@link InProcessCalls#requireRunning}), and the set of these beans is the list of edges that really run in-process.
 *
 * @param caller the consuming module, for example {@code catalog}
 * @param callee the called module, for example {@code offers}
 */
public record InProcessEdge(String caller, String callee) {
}
