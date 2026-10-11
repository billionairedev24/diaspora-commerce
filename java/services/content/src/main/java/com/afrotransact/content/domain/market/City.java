package com.afrotransact.content.domain.market;

import java.util.Locale;
import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * A city of a state / province in Markets &amp; locations.
 *
 * @param name   display name (unique per region, case-insensitive)
 * @param status LIVE, PLANNED or OFF
 */
public record City(String name, LocationStatus status) {

    /** Longest city name accepted. */
    public static final int MAX_NAME = 80;

    public City {
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > MAX_NAME || name.contains("/")) {
            throw new ContentRuleViolation("content.invalid-city", "City names need 1 to " + MAX_NAME
                    + " characters and no '/'", Map.of("cities", "Invalid city name"));
        }
        status = status == null ? LocationStatus.PLANNED : status;
    }

    /** Case-insensitive identity within a region. */
    public String key() {
        return name.toLowerCase(Locale.ROOT);
    }

    public City withStatus(LocationStatus next) {
        return new City(name, next);
    }
}
