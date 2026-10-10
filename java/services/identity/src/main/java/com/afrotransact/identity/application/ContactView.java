package com.afrotransact.identity.application;

import java.util.List;

/**
 * How to reach a user, for notifications-service ({@code GET /v1/internal/users/{id}/contact}).
 *
 * @param phoneVerified true only when the account's current phone was confirmed with an SMS code (phone
 *                      verification or SMS sign-in enrolment); reset when the number changes. Callers must not
 *                      text an unverified number
 * @param market        ISO country of the default delivery address (else of the active organisation), or null
 */
public record ContactView(String userId, String firstName, String email, boolean emailVerified, String phone,
                          boolean phoneVerified, String locale, String timeZone, String market,
                          List<PushTarget> pushTokens, ChannelPreferences preferences, boolean marketingOptIn) {

    /**
     * @param platform push transport, {@code fcm} or {@code apns} (what notifications-service routes on)
     * @param app      {@code customer} or {@code driver}
     * @param os       {@code ios}, {@code android} or {@code web}
     */
    public record PushTarget(String token, String platform, String app, String os) {
    }

    /** Account-level channel switches (false = drop the channel except for security messages). */
    public record ChannelPreferences(boolean email, boolean sms, boolean push) {
    }
}
