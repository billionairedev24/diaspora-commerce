package com.afrotransact.orders.domain;

import java.time.Instant;

/** Test fixtures for the staff shipping settings. */
public final class ShippingSettingsFixtures {

    private ShippingSettingsFixtures() {
    }

    public static ShippingSettings of(long flatFeeMinor, long freeAboveMinor, boolean carrierEnabled) {
        return of(flatFeeMinor, Long.valueOf(freeAboveMinor), carrierEnabled);
    }

    public static ShippingSettings of(long flatFeeMinor, Long freeAboveMinor, boolean carrierEnabled) {
        return new ShippingSettings("USD", flatFeeMinor, freeAboveMinor, carrierEnabled, 1,
                Instant.parse("2026-11-01T00:00:00Z"), "staff-1", "Launch default");
    }
}
