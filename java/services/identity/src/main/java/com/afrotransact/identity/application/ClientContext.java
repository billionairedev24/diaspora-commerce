package com.afrotransact.identity.application;

/**
 * The end user's browser as reported by the BFF (it calls identity server-to-server, so the request's own address is
 * the BFF's).
 *
 * @param ip        end user's IP address (IP throttle key and audit)
 * @param userAgent end user's User-Agent (audit, trusted-device label)
 */
public record ClientContext(String ip, String userAgent) {
}
