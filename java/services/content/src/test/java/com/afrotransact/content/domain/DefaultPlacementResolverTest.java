package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.content.domain.placement.Audience;
import com.afrotransact.content.domain.placement.DefaultPlacementResolver;
import com.afrotransact.content.domain.placement.Device;
import com.afrotransact.content.domain.placement.FrequencyCap;
import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.PlacementContext;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.domain.placement.Slot;
import com.afrotransact.content.domain.placement.SlotKey;
import com.afrotransact.content.domain.placement.Targeting;
import org.junit.jupiter.api.Test;

class DefaultPlacementResolverTest {

    private final DefaultPlacementResolver resolver = new DefaultPlacementResolver();
    private final Map<SlotKey, Slot> slots = slots();

    @Test
    void filtersByMarketZipPrefixDeviceAndAudience() {
        Placement everyone = Placements.live("all", SlotKey.HERO_CAROUSEL, Targeting.everyone(), 1);
        Placement houstonMobile = Placements.live("hou", SlotKey.HERO_CAROUSEL,
                Placements.targeting(Set.of("us"), Set.of("770", "772"), Set.of(Device.MOBILE), Audience.ALL), 5);
        Placement canada = Placements.live("ca", SlotKey.HERO_CAROUSEL,
                Placements.targeting(Set.of("CA"), Set.of(), Set.of(), Audience.ALL), 9);
        Placement newcomers = Placements.live("new", SlotKey.HERO_CAROUSEL,
                Placements.targeting(Set.of(), Set.of(), Set.of(), Audience.NEW), 3);
        List<Placement> all = List.of(everyone, houstonMobile, canada, newcomers);

        assertThat(ids(ctx("US", "77042", Device.MOBILE, false))).isEqualTo(List.of("hou", "new", "all"));
        assertThat(ids(resolver.resolve(all, slots, ctx("US", "77042", Device.MOBILE, true), Placements.NOW)))
                .containsExactly("hou", "all");
        assertThat(ids(resolver.resolve(all, slots, ctx("US", "10001", Device.MOBILE, null), Placements.NOW)))
                .containsExactly("all");
        assertThat(ids(resolver.resolve(all, slots, ctx("US", "77042", Device.DESKTOP, null), Placements.NOW)))
                .containsExactly("all");
        assertThat(ids(resolver.resolve(all, slots, ctx("CA", null, null, null), Placements.NOW)))
                .containsExactly("ca", "all");
    }

    @Test
    void dmaMatchesWhenZipDoesNot() {
        Placement atlanta = Placements.live("atl", SlotKey.DISPLAY_BANNER,
                new Targeting(Set.of(), Set.of(), Set.of("303"), Set.of("524"), Set.of(), Audience.ALL, Set.of()), 1);
        PlacementContext byDma = new PlacementContext("US", null, "30097", "524", null, null, Set.of(), null, Map.of());
        PlacementContext elsewhere = new PlacementContext("US", null, "30097", "501", null, null, Set.of(), null, Map.of());
        assertThat(resolver.resolve(List.of(atlanta), slots, byDma, Placements.NOW)).hasSize(1);
        assertThat(resolver.resolve(List.of(atlanta), slots, elsewhere, Placements.NOW)).isEmpty();
    }

    @Test
    void communityAndCategoryTargetingNeedAMatchingContext() {
        Placement diwali = Placements.live("diwali", SlotKey.CATEGORY_BANNER,
                new Targeting(Set.of(), Set.of("Indian"), Set.of(), Set.of(), Set.of(), Audience.ALL, Set.of("spices")), 1);
        PlacementContext indianSpices = new PlacementContext("US", SlotKey.CATEGORY_BANNER, null, null, null, null,
                Set.of("indian", "ghanaian"), "Spices", Map.of());
        PlacementContext noCommunity = new PlacementContext("US", SlotKey.CATEGORY_BANNER, null, null, null, null,
                Set.of(), "spices", Map.of());
        assertThat(resolver.resolve(List.of(diwali), slots, indianSpices, Placements.NOW)).hasSize(1);
        assertThat(resolver.resolve(List.of(diwali), slots, noCommunity, Placements.NOW)).isEmpty();
    }

    @Test
    void honoursScheduleWindowAndStatus() {
        Placement past = Placements.live("past", SlotKey.ANNOUNCEMENT_BAR, Targeting.everyone(), 1,
                FrequencyCap.unlimited(), Placements.window(-48, -1));
        Placement future = Placements.live("future", SlotKey.ANNOUNCEMENT_BAR, Targeting.everyone(), 1,
                FrequencyCap.unlimited(), Placements.window(2, 10));
        Placement now = Placements.live("now", SlotKey.ANNOUNCEMENT_BAR, Targeting.everyone(), 1,
                FrequencyCap.unlimited(), Placements.window(-1, 1));
        Placement draft = new Placement("draft", SlotKey.ANNOUNCEMENT_BAR, "d", "{}", Targeting.everyone(),
                Placements.window(-1, null), FrequencyCap.unlimited(), 50, null,
                PlacementStatus.DRAFT, "a", null, null, null, 0,
                Placements.NOW, Placements.NOW);
        assertThat(ids(resolver.resolve(List.of(past, future, now, draft), slots, PlacementContext.of("US", null),
                Placements.NOW))).containsExactly("now");
        assertThat(ids(resolver.resolve(List.of(past, future, now), slots, PlacementContext.of("US", null),
                Placements.NOW.plusSeconds(3 * 3600)))).containsExactly("future");
    }

    @Test
    void frequencyCapHidesPlacementsSeenTooOften() {
        Placement capped = Placements.live("capped", SlotKey.HERO_CAROUSEL, Targeting.everyone(), 9,
                new FrequencyCap(3, 24), Placements.window(-1, null));
        Placement free = Placements.live("free", SlotKey.HERO_CAROUSEL, Targeting.everyone(), 1);
        PlacementContext seenTwice = new PlacementContext("US", null, null, null, null, null, Set.of(), null,
                Map.of("capped", 2, "free", 100));
        PlacementContext seenThrice = new PlacementContext("US", null, null, null, null, null, Set.of(), null,
                Map.of("capped", 3));
        assertThat(ids(resolver.resolve(List.of(capped, free), slots, seenTwice, Placements.NOW)))
                .containsExactly("capped", "free");
        assertThat(ids(resolver.resolve(List.of(capped, free), slots, seenThrice, Placements.NOW)))
                .containsExactly("free");
    }

    @Test
    void ordersByPriorityAndLimitsPerSlot() {
        Placement a1 = Placements.live("a1", SlotKey.ANNOUNCEMENT_BAR, Targeting.everyone(), 1);
        Placement a2 = Placements.live("a2", SlotKey.ANNOUNCEMENT_BAR, Targeting.everyone(), 30);
        Placement a3 = Placements.live("a3", SlotKey.ANNOUNCEMENT_BAR, Targeting.everyone(), 20);
        Placement x = Placements.live("x", SlotKey.CHECKOUT_CROSS_SELL, Targeting.everyone(), 1);
        Placement y = Placements.live("y", SlotKey.CHECKOUT_CROSS_SELL, Targeting.everyone(), 2);
        List<Placement> out = resolver.resolve(List.of(a1, a2, a3, x, y), slots, PlacementContext.of("US", null),
                Placements.NOW);
        assertThat(ids(out)).containsExactly("a2", "a3", "a1", "y");
        assertThat(ids(resolver.resolve(List.of(a1, a2, a3, x, y), slots,
                PlacementContext.of("US", SlotKey.CHECKOUT_CROSS_SELL), Placements.NOW))).containsExactly("y");
    }

    @Test
    void bundleKeepsEveryServablePlacementOfTheMarketPerSlot() {
        Placement us = Placements.live("us", SlotKey.HERO_CAROUSEL,
                Placements.targeting(Set.of("US"), Set.of("770"), Set.of(), Audience.ALL), 1);
        Placement ca = Placements.live("ca", SlotKey.HERO_CAROUSEL,
                Placements.targeting(Set.of("CA"), Set.of(), Set.of(), Audience.ALL), 1);
        Placement global = Placements.live("g", SlotKey.ANNOUNCEMENT_BAR, Targeting.everyone(), 1);
        Map<SlotKey, List<Placement>> bundle = resolver.bundle(List.of(us, ca, global), "US", Placements.NOW);
        assertThat(ids(bundle.get(SlotKey.HERO_CAROUSEL))).containsExactly("us");
        assertThat(ids(bundle.get(SlotKey.ANNOUNCEMENT_BAR))).containsExactly("g");
    }

    private List<String> ids(PlacementContext ctx) {
        Placement everyone = Placements.live("all", SlotKey.HERO_CAROUSEL, Targeting.everyone(), 1);
        Placement houstonMobile = Placements.live("hou", SlotKey.HERO_CAROUSEL,
                Placements.targeting(Set.of("US"), Set.of("770"), Set.of(Device.MOBILE), Audience.ALL), 5);
        Placement newcomers = Placements.live("new", SlotKey.HERO_CAROUSEL,
                Placements.targeting(Set.of(), Set.of(), Set.of(), Audience.NEW), 3);
        return ids(resolver.resolve(List.of(everyone, houstonMobile, newcomers), slots, ctx, Placements.NOW));
    }

    private static List<String> ids(List<Placement> list) {
        return list.stream().map(Placement::id).toList();
    }

    private static PlacementContext ctx(String market, String zip, Device device, Boolean returning) {
        return new PlacementContext(market, null, zip, null, device, returning, Set.of(), null, Map.of());
    }

    private static Map<SlotKey, Slot> slots() {
        Map<SlotKey, Slot> m = new EnumMap<>(SlotKey.class);
        for (SlotKey k : SlotKey.values()) {
            int max = k == SlotKey.CHECKOUT_CROSS_SELL ? 1 : 5;
            m.put(k, new Slot(k, k.key(), "", max, "", "{}"));
        }
        return m;
    }
}
