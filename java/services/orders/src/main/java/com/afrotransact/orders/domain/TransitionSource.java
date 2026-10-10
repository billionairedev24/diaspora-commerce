package com.afrotransact.orders.domain;

import java.util.Locale;

/** Channel a transition arrived through. */
public enum TransitionSource {
    API,
    CARRIER,
    DRIVER,
    SYSTEM;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
