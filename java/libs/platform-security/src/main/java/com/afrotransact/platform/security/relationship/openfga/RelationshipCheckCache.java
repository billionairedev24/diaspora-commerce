package com.afrotransact.platform.security.relationship.openfga;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Small per-instance TTL cache of check results (allowed and denied). Bounded: when full, expired entries are
 * dropped and, if still full, everything is. Other instances keep their entries until the TTL passes, so a revoked
 * membership stops working within {@code cacheTtl} everywhere.
 */
class RelationshipCheckCache {

    private final long ttlNanos;
    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    RelationshipCheckCache(Duration ttl, int maxEntries, LongSupplier nanoClock) {
        this.ttlNanos = ttl.toNanos();
        this.maxEntries = Math.max(1, maxEntries);
        this.nanoClock = nanoClock;
    }

    boolean enabled() {
        return ttlNanos > 0;
    }

    /** The cached result, or null when absent or expired. */
    Boolean get(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            return null;
        }
        if (nanoClock.getAsLong() - e.expiresAt() > 0) {
            entries.remove(key, e);
            return null;
        }
        return e.allowed();
    }

    void put(String key, boolean allowed) {
        if (entries.size() >= maxEntries) {
            long now = nanoClock.getAsLong();
            entries.values().removeIf(e -> now - e.expiresAt() > 0);
            if (entries.size() >= maxEntries) {
                entries.clear();
            }
        }
        entries.put(key, new Entry(allowed, nanoClock.getAsLong() + ttlNanos));
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private record Entry(boolean allowed, long expiresAt) {
    }
}
