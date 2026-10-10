package com.afrotransact.identity.domain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which entry point the person signed up from. The form never asks for a role: every account gets
 * {@link RoleKeys#CUSTOMER}; the seller and provider entry points add the matching applicant role server-side.
 */
public enum SignUpIntent {
    SHOP,
    SELL,
    PROVIDE;

    public Set<String> roles() {
        Set<String> roles = new LinkedHashSet<>();
        roles.add(RoleKeys.CUSTOMER);
        if (this == SELL) {
            roles.add(RoleKeys.SELLER_APPLICANT);
        } else if (this == PROVIDE) {
            roles.add(RoleKeys.PROVIDER_APPLICANT);
        }
        return roles;
    }

    public static SignUpIntent parse(String value) {
        if (value == null || value.isBlank()) {
            return SHOP;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
