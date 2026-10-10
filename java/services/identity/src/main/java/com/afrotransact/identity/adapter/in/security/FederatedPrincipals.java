package com.afrotransact.identity.adapter.in.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Helpers shared by the social login user services. */
final class FederatedPrincipals {

    /** Attribute holding the account id; it is the principal name (token {@code sub}). */
    static final String ACCOUNT_ID = "at_uid";

    private FederatedPrincipals() {
    }

    /** The account already signed in on this browser session, if any (used to link a provider). */
    static Optional<UUID> signedInUser() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || current instanceof AnonymousAuthenticationToken || !current.isAuthenticated()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(current.getName()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
