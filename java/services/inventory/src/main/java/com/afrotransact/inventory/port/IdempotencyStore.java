package com.afrotransact.inventory.port;

import java.util.Optional;

/** Remembers responses of writes made with an {@code Idempotency-Key} so retries get the same answer. */
public interface IdempotencyStore {

    /**
     * Claims {@code key} in the current transaction. A concurrent claim of the same key waits until the first
     * transaction ends.
     *
     * @return empty when the key is new (proceed, then {@link #complete}); otherwise the stored response
     * @throws com.afrotransact.platform.web.error.ConflictException when the key was used for a different request
     */
    <T> Optional<T> claim(String scope, String key, String fingerprint, Class<T> responseType);

    void complete(String scope, String key, Object response);
}
