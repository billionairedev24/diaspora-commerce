package com.afrotransact.retail.port;

import java.util.Optional;

/** Stores responses of idempotent writes by (scope, Idempotency-Key). */
public interface IdempotencyStore {

    /**
     * Claims {@code key} for a request with {@code fingerprint}. Returns the stored response when the key was used
     * before with the same request; fails with a conflict when it was used for a different one.
     */
    <T> Optional<T> claim(String scope, String key, String fingerprint, Class<T> responseType);

    void complete(String scope, String key, Object response);
}
