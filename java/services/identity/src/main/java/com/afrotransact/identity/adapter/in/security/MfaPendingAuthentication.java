package com.afrotransact.identity.adapter.in.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * First factor done, second factor pending. Not authenticated, so the authorization endpoint will not issue a code;
 * the access-denied handler sends the browser to {@code /login/mfa} instead.
 */
public final class MfaPendingAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final Authentication firstFactor;

    public MfaPendingAuthentication(Authentication firstFactor) {
        super(List.of());
        this.firstFactor = firstFactor;
    }

    public Authentication firstFactor() {
        return firstFactor;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return firstFactor.getName();
    }

    @Override
    public String getName() {
        return firstFactor.getName();
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("A pending MFA authentication cannot be marked authenticated");
        }
        super.setAuthenticated(false);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MfaPendingAuthentication other && super.equals(other)
                && firstFactor.equals(other.firstFactor);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + firstFactor.hashCode();
    }
}
