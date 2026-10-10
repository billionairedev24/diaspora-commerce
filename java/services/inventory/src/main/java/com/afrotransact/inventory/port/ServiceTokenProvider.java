package com.afrotransact.inventory.port;

import java.util.Optional;

/** Client-credentials token of inventory-service (client {@code inventory-service}), cached until near expiry. */
public interface ServiceTokenProvider {

    /** The bearer token, or empty when no client secret is configured (local profile). */
    Optional<String> token();
}
