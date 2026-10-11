package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Account settings that are not notification switches.
 *
 * @param jobTitle     shown on back-office profiles, or null
 * @param dateFormat   how dates are written; AUTO follows the language
 * @param numberFormat digit grouping and decimal mark; AUTO follows the language
 * @param tableDensity default row height of back-office tables
 */
public record UserSettings(UUID userId, String jobTitle, DateFormat dateFormat, NumberFormat numberFormat,
                           TableDensity tableDensity, Instant updatedAt) {

    /** Longest job title kept. */
    public static final int JOB_TITLE_MAX = 80;

    /** Date order: AUTO (from the language), month-day-year, day-month-year or year-month-day. */
    public enum DateFormat { AUTO, MDY, DMY, YMD }

    /** Grouping and decimal separators: AUTO, 1,234.5 / 1.234,5 / 1 234,5. */
    public enum NumberFormat { AUTO, COMMA_DOT, DOT_COMMA, SPACE_COMMA }

    /** Default back-office table row height. */
    public enum TableDensity { COMFORTABLE, COMPACT }

    public static UserSettings defaults(UUID userId) {
        return new UserSettings(userId, null, DateFormat.AUTO, NumberFormat.AUTO, TableDensity.COMFORTABLE, null);
    }

    /** Trimmed job title, null when blank. */
    public static String cleanJobTitle(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) {
            return null;
        }
        String t = jobTitle.strip().replaceAll("\\s+", " ");
        return t.length() > JOB_TITLE_MAX ? t.substring(0, JOB_TITLE_MAX) : t;
    }

    public UserSettings withJobTitle(String title, Instant now) {
        return new UserSettings(userId, cleanJobTitle(title), dateFormat, numberFormat, tableDensity, now);
    }

    public UserSettings withDisplay(DateFormat date, NumberFormat number, TableDensity density, Instant now) {
        return new UserSettings(userId, jobTitle, date, number, density, now);
    }
}
