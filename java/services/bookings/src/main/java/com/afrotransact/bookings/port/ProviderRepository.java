package com.afrotransact.bookings.port;

import java.util.Optional;

import com.afrotransact.bookings.domain.Provider;

/** Persistence port for provider booking setups. */
public interface ProviderRepository {

    Optional<Provider> find(String orgId);

    /** Locks the provider row (SELECT … FOR UPDATE) for the rest of the transaction. */
    Optional<Provider> lock(String orgId);

    Provider insert(Provider provider);

    /** Optimistic update on {@code version}; throws a conflict when someone else changed it. */
    Provider update(Provider provider);
}
