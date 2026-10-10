package com.afrotransact.content.domain.placement;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The request context placements are resolved for.
 *
 * @param market      ISO country code (required)
 * @param slot        a single slot, or null for every slot
 * @param zip         deliver-to ZIP
 * @param dma         DMA code
 * @param device      device class
 * @param returning   true for returning visitors, false for new, null when unknown
 * @param communities communities the visitor follows
 * @param category    category page being rendered
 * @param seen        impressions per placement id within each placement's cap window (frequency caps)
 */
public record PlacementContext(String market, SlotKey slot, String zip, String dma, Device device, Boolean returning,
                               Set<String> communities, String category, Map<String, Integer> seen) {

    public PlacementContext {
        market = market == null ? null : market.trim().toUpperCase(Locale.ROOT);
        zip = zip == null || zip.isBlank() ? null : zip.trim();
        dma = dma == null || dma.isBlank() ? null : dma.trim();
        communities = communities == null ? Set.of() : communities.stream()
                .map(c -> c.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        category = category == null || category.isBlank() ? null : category.trim().toLowerCase(Locale.ROOT);
        seen = seen == null ? Map.of() : Map.copyOf(seen);
    }

    public static PlacementContext of(String market, SlotKey slot) {
        return new PlacementContext(market, slot, null, null, null, null, Set.of(), null, Map.of());
    }

    public int seenCount(String placementId) {
        return seen.getOrDefault(placementId, 0);
    }
}
