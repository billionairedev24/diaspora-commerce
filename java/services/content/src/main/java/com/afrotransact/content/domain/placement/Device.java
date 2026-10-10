package com.afrotransact.content.domain.placement;

import java.util.Locale;

import com.afrotransact.content.domain.ContentRuleViolation;

/** Device class used for targeting. */
public enum Device {
    DESKTOP,
    MOBILE,
    TABLET,
    APP;

    public static Device parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ContentRuleViolation("content.unknown-device", "Unknown device " + raw);
        }
    }
}
