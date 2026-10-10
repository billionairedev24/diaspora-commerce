package com.afrotransact.payments.port;

import java.util.Optional;

/** Remembers responses of writes made with an {@code Idempotency-Key} so retries get the same answer. */
public interface IdempotencyStore {

    /**
     * Claims {@code key} in the current transaction.
     *
     * @return empty when the key is new (proceed, then {@link #complete}); otherwise the stored response
     */
    <T> Optional<T> claim(String scope, String key, String fingerprint, Class<T> responseType);

    void complete(String scope, String key, Object response);
}
