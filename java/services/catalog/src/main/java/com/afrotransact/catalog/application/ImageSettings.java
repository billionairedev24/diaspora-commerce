package com.afrotransact.catalog.application;

import java.time.Duration;
import java.util.Objects;

/**
 * The image rules in catalog (S390, S392). The thresholds themselves live in media-service; these settings say how
 * catalog applies its verdicts.
 *
 * @param mode           {@link Mode#OFF} asks media nothing, {@link Mode#SHADOW} asks and records what would be
 *                       refused but refuses nothing, {@link Mode#ENFORCE} refuses a main image that is rejected,
 *                       held or unknown
 * @param graceDays      how long a listing that fails the rules stays visible before the hide step may limit it (60)
 * @param hideFailing    whether the hide step is on: listings still failing after the grace period are hidden from
 *                       search and the buy box (nothing is deleted). Off until the owner switches it on
 * @param rescoreBatch   listings measured per run of the back-fill job
 * @param rescoreEvery   how often the back-fill job runs
 * @param recheckAfter   a stored score older than this is measured again (0 = never)
 */
public record ImageSettings(Mode mode, int graceDays, boolean hideFailing, int rescoreBatch, Duration rescoreEvery,
                            Duration recheckAfter) {

    /** How the verdicts are applied. */
    public enum Mode {
        OFF, SHADOW, ENFORCE
    }

    public ImageSettings {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(rescoreEvery, "rescoreEvery");
        Objects.requireNonNull(recheckAfter, "recheckAfter");
        if (graceDays < 0 || rescoreBatch < 1 || rescoreBatch > 200) {
            throw new IllegalArgumentException("graceDays must be 0 or more and rescoreBatch 1 to 200");
        }
    }

    public boolean enforcing() {
        return mode == Mode.ENFORCE;
    }

    public boolean checking() {
        return mode != Mode.OFF;
    }

    /** The same settings with another mode (tests). */
    public ImageSettings withMode(Mode next) {
        return new ImageSettings(next, graceDays, hideFailing, rescoreBatch, rescoreEvery, recheckAfter);
    }
}
