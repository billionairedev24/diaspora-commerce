package com.afrotransact.ai.domain;

import java.time.Duration;
import java.util.List;

/**
 * How one task is served: models in fallback order, the per-call timeout and how often a model is retried on
 * 429 / 5xx before falling back to the next one.
 *
 * @param task       the task
 * @param models     OpenRouter model ids, primary first (for example {@code anthropic/claude-sonnet-4.5})
 * @param timeout    per-call timeout
 * @param maxRetries retries of the same model on rate limits and server errors (0 = no retry)
 * @param backoff    first retry delay; doubled on each further retry
 */
public record ModelRoute(TaskType task, List<String> models, Duration timeout, int maxRetries, Duration backoff) {

    public ModelRoute {
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }
        models = models == null ? List.of() : models.stream().map(String::trim).filter(m -> !m.isEmpty())
                .distinct().toList();
        if (models.isEmpty()) {
            throw new IllegalArgumentException("route " + task + " needs at least one model");
        }
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        backoff = backoff == null ? Duration.ofMillis(250) : backoff;
    }

    /** Delay before retry number {@code retry} (1-based): backoff, 2 × backoff, 4 × backoff … capped at 8 s. */
    public Duration backoffFor(int retry) {
        long millis = backoff.toMillis() << Math.min(Math.max(retry - 1, 0), 5);
        return Duration.ofMillis(Math.min(millis, 8_000));
    }
}
