package com.afrotransact.ai.application;

import java.time.Instant;

/**
 * The ONE place that says what the gateway-wide daily AI spend cap is right now. The ai-gateway guard, the spend-today
 * report and the cap endpoints all read it here, and the MCP server's AI-calling tools reach the same value because they
 * call the model only through ai-gateway with the caller's token (the MCP server has no model client).
 *
 * <p>The value is the saved cap while one exists, otherwise the deployment default ({@code AI_GLOBAL_DAILY_COST_USD}),
 * and never more than the hard ceiling ({@code AI_GLOBAL_DAILY_COST_MAX_USD}). 0 means AI is switched off. A replica keeps
 * what it read for a few seconds (at most 30); saving a change drops that copy at once on the replica that saved it.
 */
public interface AiSpendCapPolicy {

    /** The cap in force, from a short-lived cache. If the database cannot be read the last known value is kept. */
    Cap current();

    /** Cap in force in micro-dollars per UTC day (0 = AI off). */
    default long capMicros() {
        return current().capMicros();
    }

    /** Forgets the cached value so the next read goes to the database (called after a save). */
    void invalidate();

    /** Where the value in force comes from. */
    enum Source {
        DEFAULT, SAVED
    }

    /**
     * @param capMicros     in force, never above {@code ceilingMicros}
     * @param source        {@code DEFAULT} until a value is saved
     * @param defaultMicros the deployment default
     * @param ceilingMicros the hard ceiling
     * @param version       0 while nothing is saved; the ETag of the saved value otherwise
     * @param changedBy     subject of who saved it (null for the default)
     * @param changedAt     when it was saved (null for the default)
     * @param reason        why (null for the default)
     */
    record Cap(long capMicros, Source source, long defaultMicros, long ceilingMicros, long version, String changedBy,
               Instant changedAt, String reason) {
    }
}
