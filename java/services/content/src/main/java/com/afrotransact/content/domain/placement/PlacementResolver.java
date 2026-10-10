package com.afrotransact.content.domain.placement;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Picks the placements a context should see, and compiles per-market bundles for the CDN. */
public interface PlacementResolver {

    /**
     * Servable placements matching the context's targeting and frequency caps, highest priority first,
     * at most {@link Slot#maxItems()} per slot, grouped in slot order.
     */
    List<Placement> resolve(Collection<Placement> candidates, Map<SlotKey, Slot> slots, PlacementContext ctx,
                            Instant now);

    /**
     * Every placement servable now in {@code market}, per slot and priority-ordered, without location, device,
     * audience or cap filtering (the edge applies those with the targeting included in the bundle).
     */
    Map<SlotKey, List<Placement>> bundle(Collection<Placement> candidates, String market, Instant now);
}
