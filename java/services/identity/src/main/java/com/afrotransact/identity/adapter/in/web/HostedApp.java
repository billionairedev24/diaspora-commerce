package com.afrotransact.identity.adapter.in.web;

import java.util.Locale;

/**
 * The app an authorization request comes from, which the hosted pages brand themselves for (copy in
 * {@code messages.properties} under {@code auth.<key>.*}).
 */
enum HostedApp {
    /** Storefront (web via gateway-bff, or the customer app). */
    SHOP(true),
    /** Seller Center & Provider Hub. */
    SELLER(true),
    /** Staff console: staff accounts are created by an access administrator, so no self sign-up. */
    STAFF(false),
    /** Driver app: drivers are onboarded by operations. */
    DRIVER(false),
    /** An AI assistant connecting over MCP. */
    AGENT(true);

    private final boolean selfSignUp;

    HostedApp(boolean selfSignUp) {
        this.selfSignUp = selfSignUp;
    }

    /** Message key segment, for example {@code shop}. */
    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether "Create account" is offered. */
    boolean selfSignUp() {
        return selfSignUp;
    }
}
