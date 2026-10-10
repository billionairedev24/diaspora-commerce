package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;

/**
 * A provider organisation's booking setup: its time zone, booking rules and the current cancellation policy.
 *
 * @param strikes reliability strikes from late provider cancellations
 */
public record Provider(String orgId, String displayName, ZoneId timeZone, String currency, String location,
                       BookingRules rules, CancellationPolicy policy, int strikes, Instant createdAt,
                       Instant updatedAt, long version) {

    public Provider {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(timeZone, "timeZone");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(policy, "policy");
        if (displayName == null || displayName.isBlank()) {
            throw new BookingsRuleViolation("bookings.invalid-provider", "Business name is required");
        }
        displayName = displayName.trim();
        currency = currency == null ? "USD" : currency.trim().toUpperCase(Locale.ROOT);
        if (currency.length() != 3) {
            throw new BookingsRuleViolation("bookings.invalid-provider", "Currency must be an ISO 4217 code");
        }
    }

    public static Provider create(String orgId, String displayName, ZoneId zone, String currency, String location,
                                  BookingRules rules, Instant now) {
        return new Provider(orgId, displayName, zone, currency, location, rules == null ? BookingRules.defaults()
                : rules, CancellationPolicy.moderate(), 0, now, now, 0);
    }

    public Provider withSettings(String newName, ZoneId zone, String newLocation, BookingRules newRules,
                                 Instant now) {
        return new Provider(orgId, newName, zone, currency, newLocation, newRules, policy, strikes, createdAt, now,
                version);
    }

    public Provider withPolicy(CancellationPolicy newPolicy, Instant now) {
        return new Provider(orgId, displayName, timeZone, currency, location, rules, newPolicy, strikes, createdAt,
                now, version);
    }

    public Provider withStrike(Instant now) {
        return new Provider(orgId, displayName, timeZone, currency, location, rules, policy, strikes + 1, createdAt,
                now, version);
    }
}
