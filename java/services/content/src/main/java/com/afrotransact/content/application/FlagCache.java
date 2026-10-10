package com.afrotransact.content.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.afrotransact.content.domain.flag.FeatureFlag;
import com.afrotransact.content.port.FlagRepository;
import org.springframework.stereotype.Component;

/** All flags, cached per replica for at most the configured TTL (30 s): the kill-switch propagation bound. */
@Component
public class FlagCache {

    private final TtlCache<Snapshot> cache;

    FlagCache(FlagRepository flags, ContentSettings settings, Clock clock) {
        this.cache = new TtlCache<>(() -> {
            List<FeatureFlag> all = flags.findAll();
            Map<String, FeatureFlag> byKey = new LinkedHashMap<>();
            all.forEach(f -> byKey.put(f.key(), f));
            String etag = Fingerprint.of(all.stream().map(f -> f.key() + ":" + f.version()).collect(Collectors.joining(",")));
            return new Snapshot(byKey, etag);
        }, settings.flagCacheTtl(), clock);
    }

    public Snapshot get() {
        return cache.get();
    }

    public void invalidate() {
        cache.invalidate();
    }

    /**
     * Cached flags.
     *
     * @param flags by key, in key order
     * @param etag  fingerprint of keys and versions
     */
    public record Snapshot(Map<String, FeatureFlag> flags, String etag) {
    }
}
