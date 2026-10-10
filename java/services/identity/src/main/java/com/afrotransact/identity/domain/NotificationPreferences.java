package com.afrotransact.identity.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Account-level notification switches read by notifications-service. A channel switched off drops every message on
 * it except security messages (one-time codes). Marketing needs explicit opt-in. SMS additionally needs the TCPA
 * consent record notifications-service keeps.
 *
 * @param locale   BCP 47 tag for templates (e.g. {@code fr-CA}), or null for the platform default
 * @param timeZone IANA zone for quiet hours, or null for the market's zone
 */
public record NotificationPreferences(UUID userId, boolean email, boolean sms, boolean push, boolean marketingOptIn,
                                      Instant marketingOptInAt, String locale, String timeZone, Instant updatedAt) {

    public static NotificationPreferences defaults(UUID userId) {
        return new NotificationPreferences(userId, true, true, true, false, null, null, null, null);
    }

    /** Field errors for a locale and time zone, empty when both are valid (or blank). */
    public static Map<String, String> check(String locale, String timeZone) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (locale != null && !locale.isBlank()) {
            Locale parsed = Locale.forLanguageTag(locale.trim());
            if (parsed.getLanguage().isEmpty() || locale.trim().length() > 35) {
                errors.put("locale", "Use a language tag such as en-US or fr-CA");
            }
        }
        if (timeZone != null && !timeZone.isBlank()) {
            try {
                ZoneId.of(timeZone.trim());
            } catch (DateTimeException ex) {
                errors.put("timeZone", "Use an IANA time zone such as America/Chicago");
            }
        }
        return errors;
    }

    /** Canonical form of a valid locale tag ({@code fr-ca} → {@code fr-CA}), or null when blank. */
    public static String canonicalLocale(String locale) {
        return locale == null || locale.isBlank() ? null : Locale.forLanguageTag(locale.trim()).toLanguageTag();
    }
}
