package com.afrotransact.identity.application;

/**
 * The end user's browser or app as reported by the BFF (it calls identity server-to-server, so the request's own
 * address is the BFF's).
 *
 * @param ip          end user's IP address (IP throttle key and audit)
 * @param userAgent   end user's User-Agent (audit, trusted-device label)
 * @param appClientId native app (public OAuth client such as {@code customer-app}) the login ticket is for, or null
 *                    for the BFF's own web sessions; must be one of the configured native clients
 */
public record ClientContext(String ip, String userAgent, String appClientId) {

    public ClientContext(String ip, String userAgent) {
        this(ip, userAgent, null);
    }
}
