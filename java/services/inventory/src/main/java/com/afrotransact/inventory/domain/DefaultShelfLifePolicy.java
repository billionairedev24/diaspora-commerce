package com.afrotransact.inventory.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Receiving rule from the 1P workflow: reject expired lots and inconsistent dates; quarantine lots whose remaining
 * life is below {@code max(minDays, minFraction × shelf life)} (defaults: 90 days, 50 %).
 */
public class DefaultShelfLifePolicy implements ShelfLifePolicy {

    private final int minDays;
    private final double minFraction;

    public DefaultShelfLifePolicy(int minDays, double minFraction) {
        if (minDays < 0 || minFraction < 0 || minFraction > 1) {
            throw new IllegalArgumentException("minDays >= 0 and 0 <= minFraction <= 1");
        }
        this.minDays = minDays;
        this.minFraction = minFraction;
    }

    @Override
    public ShelfLifeDecision assess(LocalDate mfgDate, LocalDate expDate, Integer shelfLifeDays, LocalDate today) {
        if (mfgDate != null && mfgDate.isAfter(today)) {
            return reject(expDate, today, "Manufacture date " + mfgDate + " is in the future");
        }
        if (expDate == null) {
            return new ShelfLifeDecision(ShelfLifeDecision.Outcome.ACCEPT, -1, 0, "Does not expire");
        }
        if (mfgDate != null && !mfgDate.isBefore(expDate)) {
            return reject(expDate, today, "Expiry " + expDate + " must be after manufacture " + mfgDate);
        }
        long remaining = ChronoUnit.DAYS.between(today, expDate);
        if (remaining <= 0) {
            return new ShelfLifeDecision(ShelfLifeDecision.Outcome.REJECT, remaining, minDays,
                    "Lot expired on " + expDate);
        }
        Long shelfLife = shelfLifeDays != null ? Long.valueOf(shelfLifeDays)
                : mfgDate != null ? Long.valueOf(ChronoUnit.DAYS.between(mfgDate, expDate)) : null;
        long required = shelfLife == null ? minDays : Math.max(minDays, (long) Math.ceil(shelfLife * minFraction));
        if (remaining < required) {
            return new ShelfLifeDecision(ShelfLifeDecision.Outcome.QUARANTINE, remaining, required,
                    "Short-dated: " + remaining + " days of life left, at least " + required + " needed");
        }
        return new ShelfLifeDecision(ShelfLifeDecision.Outcome.ACCEPT, remaining, required, "Sellable");
    }

    private ShelfLifeDecision reject(LocalDate expDate, LocalDate today, String reason) {
        long remaining = expDate == null ? -1 : ChronoUnit.DAYS.between(today, expDate);
        return new ShelfLifeDecision(ShelfLifeDecision.Outcome.REJECT, remaining, minDays, reason);
    }
}
