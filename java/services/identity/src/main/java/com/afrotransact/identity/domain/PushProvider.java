package com.afrotransact.identity.domain;

import java.util.Locale;

/** Push transport; the wire value ({@code fcm}, {@code apns}) is what notifications-service routes on. */
public enum PushProvider {
    FCM,
    APNS;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
