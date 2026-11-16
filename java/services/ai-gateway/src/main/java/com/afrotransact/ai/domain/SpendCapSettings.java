package com.afrotransact.ai.domain;

import java.time.Duration;

/**
 * Deployment-side bounds of the editable daily spend cap, all in micro-dollars (1,000,000 = USD 1).
 *
 * @param defaultMicros the value in force until a cap is saved ({@code AI_GLOBAL_DAILY_COST_USD})
 * @param ceilingMicros the hard ceiling no saved value can exceed ({@code AI_GLOBAL_DAILY_COST_MAX_USD})
 * @param cacheTtl      how long a replica keeps the cap it read (a saved change is seen by every replica within this)
 */
public record SpendCapSettings(long defaultMicros, long ceilingMicros, Duration cacheTtl) {

    /** The smallest step of a cap: one cent. */
    public static final long STEP_MICROS = 10_000;

    public SpendCapSettings {
        if (defaultMicros < 0 || ceilingMicros < 0) {
            throw new IllegalArgumentException("spend cap bounds cannot be negative");
        }
        if (defaultMicros > ceilingMicros) {
            throw new IllegalArgumentException("AI_GLOBAL_DAILY_COST_USD (" + defaultMicros + " micro-dollars) is above"
                    + " AI_GLOBAL_DAILY_COST_MAX_USD (" + ceilingMicros + "): lower the default or raise the ceiling");
        }
        if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("the spend cap cache must be between 0 and 30 seconds");
        }
    }
}
