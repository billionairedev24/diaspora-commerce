package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * A single-value cache that reloads after a TTL. Writers on this replica invalidate it; other replicas pick up
 * changes within the TTL (the flags kill-switch SLA relies on that).
 *
 * @param <T> cached value
 */
public final class TtlCache<T> {

    private final Supplier<T> loader;
    private final Duration ttl;
    private final Clock clock;
    private volatile Entry<T> entry;

    public TtlCache(Supplier<T> loader, Duration ttl, Clock clock) {
        this.loader = loader;
        this.ttl = ttl;
        this.clock = clock;
    }

    public T get() {
        Entry<T> e = entry;
        Instant now = clock.instant();
        if (e == null || !now.isBefore(e.expiresAt())) {
            T value = loader.get();
            entry = new Entry<>(value, now.plus(ttl));
            return value;
        }
        return e.value();
    }

    public void invalidate() {
        entry = null;
    }

    private record Entry<T>(T value, Instant expiresAt) {
    }
}
