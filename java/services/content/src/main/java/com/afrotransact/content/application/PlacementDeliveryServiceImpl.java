package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.PlacementContext;
import com.afrotransact.content.domain.placement.PlacementResolver;
import com.afrotransact.content.domain.placement.SlotKey;
import org.springframework.stereotype.Service;

@Service
class PlacementDeliveryServiceImpl implements PlacementDeliveryService {

    private static final Pattern MARKET = Pattern.compile("^[A-Za-z]{2}$");

    private final PlacementCache cache;
    private final PlacementResolver resolver;
    private final Clock clock;

    PlacementDeliveryServiceImpl(PlacementCache cache, PlacementResolver resolver, Clock clock) {
        this.cache = cache;
        this.resolver = resolver;
        this.clock = clock;
    }

    @Override
    public ResolvedPlacements resolve(PlacementContext ctx) {
        requireMarket(ctx.market());
        PlacementCache.Snapshot snap = cache.get();
        List<Placement> out = resolver.resolve(snap.approved(), snap.slots(), ctx, Instant.now(clock));
        String etag = Fingerprint.of(ctx.market(), ctx.slot(), fingerprint(out));
        return new ResolvedPlacements(ctx.market(), out, etag, !ctx.seen().isEmpty());
    }

    @Override
    public PlacementBundle bundle(String market) {
        requireMarket(market);
        String m = market.toUpperCase(Locale.ROOT);
        Instant now = Instant.now(clock);
        Map<SlotKey, List<Placement>> slots = resolver.bundle(cache.get().approved(), m, now);
        String etag = Fingerprint.of("bundle", m, slots.values().stream().map(PlacementDeliveryServiceImpl::fingerprint)
                .collect(Collectors.joining("|")));
        return new PlacementBundle(m, now, slots, etag);
    }

    private static String fingerprint(List<Placement> list) {
        return list.stream().map(p -> p.id() + ":" + p.version()).collect(Collectors.joining(","));
    }

    private static void requireMarket(String market) {
        if (market == null || !MARKET.matcher(market).matches()) {
            throw new ContentRuleViolation("content.market-required", "market must be a two-letter country code",
                    Map.of("market", "Two-letter country code"));
        }
    }
}
