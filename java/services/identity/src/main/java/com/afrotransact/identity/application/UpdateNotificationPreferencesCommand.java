package com.afrotransact.identity.application;

/** Full replacement of the account's notification switches; blank locale / time zone clear them. */
public record UpdateNotificationPreferencesCommand(boolean email, boolean sms, boolean push, boolean marketingOptIn,
                                                   String locale, String timeZone) {
}
