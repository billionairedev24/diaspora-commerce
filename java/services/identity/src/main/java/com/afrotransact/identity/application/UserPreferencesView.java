package com.afrotransact.identity.application;

import java.time.Instant;

import com.afrotransact.identity.domain.UserSettings.DateFormat;
import com.afrotransact.identity.domain.UserSettings.NumberFormat;
import com.afrotransact.identity.domain.UserSettings.TableDensity;

/** Display preferences; locale and time zone are null when the account uses the platform / market default. */
public record UserPreferencesView(String locale, String timeZone, DateFormat dateFormat, NumberFormat numberFormat,
                                  TableDensity tableDensity, Instant updatedAt) {
}
