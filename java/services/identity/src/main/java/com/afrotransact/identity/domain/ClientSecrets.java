package com.afrotransact.identity.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/** Generation of OAuth client secrets and the rule for how long a rotated secret stays valid. */
public final class ClientSecrets {

    /** How long the previous secret keeps working after a rotation, so deployed callers can switch over. */
    public static final Duration ROTATION_GRACE = Duration.ofHours(24);

    private static final SecureRandom RANDOM = new SecureRandom();

    private ClientSecrets() {
    }

    /** 256 random bits, URL-safe; shown once and stored only as a hash. */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
