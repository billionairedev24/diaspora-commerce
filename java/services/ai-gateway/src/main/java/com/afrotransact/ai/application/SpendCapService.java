package com.afrotransact.ai.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.ai.application.AiSpendCapPolicy.Source;

/**
 * Settings > AI and MCP spend cap: read the daily cap with today's spend and the history of changes, and change it.
 * Viewing needs staff with {@code ai:manage} or {@code ai:cap.manage}; changing needs staff with {@code ai:cap.manage},
 * a reason of 3 to 300 characters, a value between 0 (AI off) and the hard ceiling, and the version the editor loaded.
 * The cap is per UTC day, like the usage ledger.
 */
public interface SpendCapService {

    /** Largest page of history returned. */
    int HISTORY_LIMIT = 25;

    SpendCapView view(Caller caller);

    /**
     * @param expectedVersion the version from the ETag the editor loaded (null when If-Match was not sent: refused with 428)
     */
    SpendCapView update(Caller caller, Long expectedVersion, long capMicros, String reason);

    /**
     * @param capMicros       the cap in force (0 = AI off)
     * @param source          default until a value is saved
     * @param defaultMicros   the deployment default ({@code AI_GLOBAL_DAILY_COST_USD})
     * @param ceilingMicros   the hard ceiling ({@code AI_GLOBAL_DAILY_COST_MAX_USD})
     * @param version         ETag of the saved value, 0 while the default is in force
     * @param changedBy       who saved the value in force (subject id, null for the default)
     * @param spentMicros     spent so far today, the figure the cap is checked against
     * @param remainingMicros what is left today (0 when spent has reached the cap)
     * @param canChange       the caller may change the cap
     */
    record SpendCapView(long capMicros, Source source, long defaultMicros, long ceilingMicros, long version,
                        String changedBy, Instant changedAt, String reason, LocalDate day, long spentMicros,
                        long remainingMicros, Instant resetsAt, boolean canChange, List<Change> history) {
    }

    /** One entry of the change history; {@code previousMicros} is null when the value before was the default. */
    record Change(UUID id, long version, Long previousMicros, boolean previousWasDefault, long newMicros,
                  String changedBy, Instant changedAt, String reason) {
    }
}
