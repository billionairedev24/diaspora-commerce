package com.afrotransact.identity.adapter.in.security;

import java.util.List;
import java.util.Objects;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Unauthenticated request to sign in with a first-party login ticket on the authorization endpoint. */
public final class LoginTicketAuthenticationToken extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String clientId;
    private String ticket;

    public LoginTicketAuthenticationToken(String ticket, String clientId) {
        super(List.of());
        this.ticket = ticket;
        this.clientId = clientId;
        setAuthenticated(false);
    }

    public String ticket() {
        return ticket;
    }

    public String clientId() {
        return clientId;
    }

    @Override
    public Object getCredentials() {
        return ticket;
    }

    @Override
    public Object getPrincipal() {
        return clientId;
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        ticket = null;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("A login ticket request cannot be marked authenticated");
        }
        super.setAuthenticated(false);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof LoginTicketAuthenticationToken other && super.equals(other)
                && Objects.equals(clientId, other.clientId) && Objects.equals(ticket, other.ticket);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), clientId, ticket);
    }
}
