package com.afrotransact.content.application;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.domain.placement.Slot;
import com.afrotransact.content.domain.placement.SlotKey;
import com.afrotransact.content.port.PlacementRepository;
import com.afrotransact.content.port.SlotRepository;
import org.springframework.stereotype.Component;

/** Approved (scheduled or live) placements and the slot catalogue, cached for public resolution. */
@Component
public class PlacementCache {

    private final TtlCache<Snapshot> cache;

    PlacementCache(PlacementRepository placements, SlotRepository slots, ContentSettings settings, Clock clock) {
        this.cache = new TtlCache<>(() -> {
            Map<SlotKey, Slot> bySlot = new EnumMap<>(SlotKey.class);
            slots.findAll().forEach(s -> bySlot.put(s.key(), s));
            return new Snapshot(placements.findByStatus(List.of(PlacementStatus.SCHEDULED, PlacementStatus.LIVE)),
                    bySlot);
        }, settings.placementCacheTtl(), clock);
    }

    public Snapshot get() {
        return cache.get();
    }

    public void invalidate() {
        cache.invalidate();
    }

    /**
     * Cached state.
     *
     * @param approved scheduled and live placements
     * @param slots    slot catalogue
     */
    public record Snapshot(List<Placement> approved, Map<SlotKey, Slot> slots) {
    }
}
