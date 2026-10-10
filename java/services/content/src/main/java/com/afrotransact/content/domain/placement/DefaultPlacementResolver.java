package com.afrotransact.content.domain.placement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Default {@link PlacementResolver}: priority desc, then most recent start, then id for a stable order. */
public class DefaultPlacementResolver implements PlacementResolver {

    private static final Comparator<Placement> ORDER = Comparator.comparingInt(Placement::priority).reversed()
            .thenComparing((Placement p) -> p.schedule().startsAt(), Comparator.reverseOrder())
            .thenComparing(Placement::id);

    @Override
    public List<Placement> resolve(Collection<Placement> candidates, Map<SlotKey, Slot> slots, PlacementContext ctx,
                                   Instant now) {
        Map<SlotKey, List<Placement>> bySlot = new EnumMap<>(SlotKey.class);
        candidates.stream()
                .filter(p -> ctx.slot() == null || p.slot() == ctx.slot())
                .filter(p -> p.servableAt(now))
                .filter(p -> p.targeting().matches(ctx))
                .filter(p -> p.frequencyCap().allows(ctx.seenCount(p.id())))
                .sorted(ORDER)
                .forEach(p -> bySlot.computeIfAbsent(p.slot(), k -> new ArrayList<>()).add(p));
        List<Placement> out = new ArrayList<>();
        bySlot.forEach((slot, list) -> {
            Slot def = slots.get(slot);
            int max = def == null ? list.size() : def.maxItems();
            out.addAll(list.subList(0, Math.min(max, list.size())));
        });
        return out;
    }

    @Override
    public Map<SlotKey, List<Placement>> bundle(Collection<Placement> candidates, String market, Instant now) {
        Map<SlotKey, List<Placement>> bySlot = new EnumMap<>(SlotKey.class);
        candidates.stream()
                .filter(p -> p.servableAt(now))
                .filter(p -> p.targeting().targetsMarket(market))
                .sorted(ORDER)
                .forEach(p -> bySlot.computeIfAbsent(p.slot(), k -> new ArrayList<>()).add(p));
        return bySlot;
    }
}
