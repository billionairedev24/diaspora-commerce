package com.afrotransact.ai.domain;

import java.util.List;

/** Chooses the models for a task and decides whether a failed attempt is retried or falls back. */
public interface ModelRoutingPolicy {

    ModelRoute route(TaskType task);

    /**
     * Models to try for {@code task}, in order. When {@code degrade} is set (a budget is exhausted but the feature
     * allows degrading) only the cheapest model of the route is returned.
     */
    List<String> candidates(TaskType task, boolean degrade);

    /** True when attempt {@code attempt} (1-based) of the same model failed with {@code kind} and may be retried. */
    boolean shouldRetry(TaskType task, FailureKind kind, int attempt);
}
