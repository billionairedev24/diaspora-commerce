package com.afrotransact.deliveryruns.domain;

import java.time.Instant;

/**
 * The driver cost model in force, with where it came from (S265).
 *
 * @param settings  the cost model
 * @param version   0 while the environment's defaults apply (nobody has saved), then 1, 2, … per staff save
 * @param updatedAt when staff last saved (null for the defaults)
 * @param updatedBy who saved (null for the defaults)
 * @param reason    why (null for the defaults)
 */
public record EconomicsRevision(EconomicsSettings settings, long version, Instant updatedAt, String updatedBy,
                                String reason) {

    /** The environment's defaults, before any staff save. */
    public static EconomicsRevision defaults(EconomicsSettings settings) {
        return new EconomicsRevision(settings, 0, null, null, null);
    }

    /** True once staff saved the settings (they no longer follow the environment's defaults). */
    public boolean saved() {
        return version > 0;
    }

    /** The next version, with the new numbers. */
    public EconomicsRevision next(EconomicsSettings numbers, String actor, String why, Instant now) {
        return new EconomicsRevision(numbers, version + 1, now, actor, why);
    }
}
