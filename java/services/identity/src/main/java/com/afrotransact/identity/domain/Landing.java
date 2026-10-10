package com.afrotransact.identity.domain;

/** Where a user goes after signing in. */
public enum Landing {
    SHOP("shop"),
    SELLER_ONBOARDING("seller-onboarding"),
    SELLER_CENTER("seller-center"),
    PROVIDER_ONBOARDING("provider-onboarding"),
    PROVIDER_HUB("provider-hub"),
    STAFF_CONSOLE("staff-console"),
    DRIVER_APP("driver-app");

    private final String wire;

    Landing(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
