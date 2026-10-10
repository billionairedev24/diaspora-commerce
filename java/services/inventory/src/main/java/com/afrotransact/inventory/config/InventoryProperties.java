package com.afrotransact.inventory.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.inventory.*} settings.
 *
 * @param retailOrgId          owner organisation of AfroTransact Retail (1P) stock
 * @param reservationTtl       checkout hold time before automatic release (default 15 minutes)
 * @param fefoMinRemainingDays FEFO skips lots expiring within this many days
 * @param shelfLife            receipt short-dated rule
 * @param jobs                 scheduled job settings
 */
@ConfigurationProperties(prefix = "at.inventory")
public record InventoryProperties(String retailOrgId, Duration reservationTtl, Integer fefoMinRemainingDays,
                                  ShelfLife shelfLife, Jobs jobs) {

    public InventoryProperties {
        retailOrgId = retailOrgId == null ? "org_afrotransact_retail" : retailOrgId;
        reservationTtl = reservationTtl == null ? Duration.ofMinutes(15) : reservationTtl;
        fefoMinRemainingDays = fefoMinRemainingDays == null ? 7 : fefoMinRemainingDays;
        shelfLife = shelfLife == null ? new ShelfLife(null, null) : shelfLife;
        jobs = jobs == null ? new Jobs(null, null) : jobs;
    }

    /** Short-dated rule: remaining life below max(minDays, minFraction × shelf life) is quarantined. */
    public record ShelfLife(Integer minDays, Double minFraction) {

        public ShelfLife {
            minDays = minDays == null ? 90 : minDays;
            minFraction = minFraction == null ? 0.5 : minFraction;
        }
    }

    /** Job switches; cron expressions are read directly by the scheduler annotations. */
    public record Jobs(Boolean enabled, Integer reservationSweepBatch) {

        public Jobs {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            reservationSweepBatch = reservationSweepBatch == null ? 200 : reservationSweepBatch;
        }
    }
}
