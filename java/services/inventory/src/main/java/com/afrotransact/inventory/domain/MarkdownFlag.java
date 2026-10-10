package com.afrotransact.inventory.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Nightly markdown flag for sellable lots close to expiry. */
public enum MarkdownFlag {
    NONE,
    WITHIN_60,
    WITHIN_30;

    /** Flag for a lot expiring on {@code expDate} as seen on {@code today} (node-local date). */
    public static MarkdownFlag of(LocalDate expDate, LocalDate today) {
        if (expDate == null) {
            return NONE;
        }
        long days = ChronoUnit.DAYS.between(today, expDate);
        if (days <= 30) {
            return WITHIN_30;
        }
        return days <= 60 ? WITHIN_60 : NONE;
    }
}
