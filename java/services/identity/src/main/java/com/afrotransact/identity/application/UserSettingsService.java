package com.afrotransact.identity.application;

/**
 * The signed-in user's display preferences (language, time zone, date and number format, table density) and work
 * profile (job title). Language and time zone are stored with the notification switches, so messages and quiet
 * hours follow them too; saving preferences never changes the switches.
 */
public interface UserSettingsService {

    UserPreferencesView preferences();

    /** 422 identity.invalid-preferences for an unknown language tag or time zone. */
    UserPreferencesView savePreferences(SavePreferencesCommand command);

    WorkProfileView workProfile();

    WorkProfileView saveWorkProfile(String jobTitle);
}
