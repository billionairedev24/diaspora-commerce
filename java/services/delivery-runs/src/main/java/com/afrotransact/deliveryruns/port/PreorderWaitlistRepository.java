package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.WaitlistEntry;

/** Pre-order waitlist sign-ups (one per email and area). */
public interface PreorderWaitlistRepository {

    WaitlistEntry insert(WaitlistEntry entry);

    /** Optimistic: fails with {@link ConcurrentUpdateException} when {@code entry.version()} is stale. */
    WaitlistEntry update(WaitlistEntry entry);

    Optional<WaitlistEntry> find(String email, AreaKey area);

    Optional<WaitlistEntry> findByUnsubscribeToken(String token);

    /** Active entries in a country, by email. */
    List<WaitlistEntry> findActive(String country);
}
