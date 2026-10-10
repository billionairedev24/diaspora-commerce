package com.afrotransact.bookings.port;

import java.util.Optional;
import java.util.UUID;

/** Remembers writes made with an {@code Idempotency-Key} so retries get the same booking back. */
public interface IdempotencyStore {

    /**
     * Claims {@code key} within {@code scope} in the current transaction.
     *
     * @return empty when the key is new (proceed, then {@link #complete}); otherwise the stored booking id
     */
    Optional<UUID> claim(String scope, String key, String fingerprint);

    void complete(String scope, String key, UUID bookingId);
}
