package com.afrotransact.bookings.application;

import java.time.ZoneId;

import com.afrotransact.bookings.domain.BookingRules;
import com.afrotransact.bookings.domain.CustomPolicySettings;
import com.afrotransact.bookings.domain.PolicyKind;
import com.afrotransact.bookings.domain.Provider;

/** A provider's booking setup: business name, time zone, booking rules and cancellation policy. */
public interface ProviderSetupService {

    Provider get(String orgId);

    /** Creates or updates the setup (Working hours screen rules); bookings:availability:write, not practitioners. */
    Provider saveSettings(String orgId, SettingsCommand command);

    /** Flexible, Moderate, Strict or Custom; existing bookings keep their own copy. bookings:services:write. */
    Provider savePolicy(String orgId, PolicyKind kind, CustomPolicySettings custom);

    /**
     * Provider settings.
     *
     * @param currency only used when the setup is created
     */
    record SettingsCommand(String displayName, ZoneId timeZone, String currency, String location,
                           BookingRules rules) {
    }
}
