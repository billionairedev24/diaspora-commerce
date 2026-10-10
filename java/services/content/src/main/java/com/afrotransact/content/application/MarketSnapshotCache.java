package com.afrotransact.content.application;

import java.time.Clock;

import com.afrotransact.content.domain.market.MarketSnapshot;
import com.afrotransact.content.port.MarketRepository;
import org.springframework.stereotype.Component;

/** The latest published markets snapshot, cached for eligibility checks. */
@Component
public class MarketSnapshotCache {

    private final TtlCache<MarketSnapshot> cache;

    MarketSnapshotCache(MarketRepository markets, ContentSettings settings, Clock clock) {
        this.cache = new TtlCache<>(() -> markets.snapshot(markets.latestVersion()), settings.marketCacheTtl(), clock);
    }

    public MarketSnapshot get() {
        return cache.get();
    }

    public void invalidate() {
        cache.invalidate();
    }
}
