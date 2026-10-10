package com.afrotransact.identity.application;

/**
 * @param acceptUrl Seller Center page that accepts an invitation; the token is appended as {@code ?token=}
 *                  ({@code at.identity.invitations.accept-url})
 */
public record InvitationSettings(String acceptUrl) {

    public InvitationSettings {
        acceptUrl = acceptUrl == null || acceptUrl.isBlank() ? "http://localhost:3001/invitations/accept" : acceptUrl;
    }

    /** The link sent to the invitee. Tokens are base64url, so they need no escaping. */
    public String link(String token) {
        return acceptUrl + (acceptUrl.contains("?") ? "&" : "?") + "token=" + token;
    }
}
