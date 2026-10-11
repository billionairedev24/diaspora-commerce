package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.UserSettings.DateFormat;
import com.afrotransact.identity.domain.UserSettings.NumberFormat;
import com.afrotransact.identity.domain.UserSettings.TableDensity;

/** Full replacement of the display preferences; a blank locale or time zone clears it. */
public record SavePreferencesCommand(String locale, String timeZone, DateFormat dateFormat, NumberFormat numberFormat,
                                     TableDensity tableDensity) {
}
