package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A device registered for push notifications. A token identifies one app install, so it belongs to at most one
 * account: registering it again (for example after another person signs in on the same phone) moves it.
 *
 * @param tokenHash SHA-256 of the token (unique key)
 */
public record PushDevice(UUID id, UUID userId, PushApp app, DevicePlatform platform, PushProvider provider,
                         String token, String tokenHash, String deviceName, Instant createdAt, Instant lastSeenAt) {

    public static final int MAX_TOKEN_LENGTH = 4096;
    public static final int MAX_NAME_LENGTH = 120;
    /** Devices kept per account and app; registering another evicts the least recently seen. */
    public static final int MAX_PER_APP = 10;

    /** Last characters of the token, enough to recognise it in a list without exposing it. */
    public String tokenSuffix() {
        return token.length() <= 6 ? token : token.substring(token.length() - 6);
    }
}
