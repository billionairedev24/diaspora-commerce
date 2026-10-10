package com.afrotransact.taxreturns.domain.tax;

import java.util.Locale;

/** Product tax category. Each state rule gives the state rate per category (grocery is reduced or exempt). */
public enum TaxCategory {
    /** Taxable tangible goods at the general rate. */
    GENERAL,
    /** Unprepared food for home consumption: reduced or exempt in most states. */
    GROCERY,
    /** Hot or ready-to-eat food: usually the general rate (sometimes a meals rate). */
    PREPARED_FOOD;

    /** Parses a category name, falling back to {@link #GENERAL} for blanks and unknown values. */
    public static TaxCategory parse(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            return GENERAL;
        }
    }
}
