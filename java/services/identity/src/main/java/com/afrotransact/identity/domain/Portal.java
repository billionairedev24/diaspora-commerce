package com.afrotransact.identity.domain;

import java.util.Locale;

/**
 * Where a role applies. {@code ACCOUNT} roles are self-service (customer, applicants); {@code PLATFORM} roles are
 * AfroTransact staff; {@code SELLER} and {@code PROVIDER} roles are held through an organisation membership.
 */
public enum Portal {
    ACCOUNT,
    PLATFORM,
    SELLER,
    PROVIDER;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
