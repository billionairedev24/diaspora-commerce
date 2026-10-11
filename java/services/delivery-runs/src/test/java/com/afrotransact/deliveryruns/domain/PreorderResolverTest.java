package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.PreorderResolver.Eligibility;
import com.afrotransact.deliveryruns.domain.PreorderResolver.ProductFacts;
import org.junit.jupiter.api.Test;

class PreorderResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    private final PreorderResolver resolver = new PreorderResolver();

    private static Coverage coverage(boolean enabled, CoverageArea... areas) {
        return new Coverage(new PreorderSettings(enabled, Fallback.SHIP, EnumSet.allOf(Placement.class),
                PaymentSettings.DEFAULT, null, 0), List.of(areas));
    }

    private static CoverageArea area(String key, boolean on) {
        return new CoverageArea(AreaKey.parse(key), on);
    }

    @Test
    void areaOnCityRowOverridesItsRegionRow() {
        Coverage c = coverage(true, area("US/TX", true), area("US/TX/Laredo", false), area("CA/ON/Toronto", true));
        assertThat(c.areaOn("US", "TX", "Lubbock")).as("region row covers every city").isTrue();
        assertThat(c.areaOn("US", "TX", "laredo")).as("city row wins, case-insensitive").isFalse();
        assertThat(c.areaOn("CA", "ON", "Toronto")).isTrue();
        assertThat(c.areaOn("CA", "ON", "Ottawa")).as("no region row").isFalse();
        assertThat(c.areaOn("US", "GA", "Atlanta")).isFalse();
        Coverage cityOn = coverage(true, area("US/TX", false), area("US/TX/Lubbock", true));
        assertThat(cityOn.areaOn("US", "TX", "Lubbock")).isTrue();
        assertThat(cityOn.areaOn("US", "TX", "Amarillo")).isFalse();
        assertThat(cityOn.targetOn(AreaKey.parse("US/TX"))).as("a region target needs its own row on").isFalse();
        assertThat(cityOn.targetOn(AreaKey.parse("US/TX/Lubbock"))).isTrue();
    }

    @Test
    void picksOpenThenUpcomingThenClosedAndIgnoresDrafts() {
        PreorderWindow closed = window("US/TX/Lubbock", NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(2)));
        PreorderWindow upcoming = window("US/TX/Lubbock", NOW.plus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(10)),
                NOW.plus(Duration.ofDays(12)));
        PreorderWindow draftOpen = Preorders.draft(Preorders.withDates(Preorders.lubbock(),
                NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)), LocalDate.parse("2026-10-14")));
        Coverage c = coverage(true, area("US/TX/Lubbock", true));

        Resolution r = resolver.resolve("US", "TX", "lubbock", List.of(closed, upcoming, draftOpen), c, Map.of(),
                false, NOW);
        assertThat(r.stage()).isEqualTo(PreorderStatus.UPCOMING);
        assertThat(r.window()).isEqualTo(upcoming);
        assertThat(r.countdownTarget()).isEqualTo(upcoming.details().opensAt());
        assertThat(r.on()).isTrue();
        assertThat(r.count()).as("no live count while upcoming").isNull();

        PreorderWindow open = window("US/TX", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(5)),
                NOW.plus(Duration.ofDays(7)));
        Resolution o = resolver.resolve("US", "TX", "Lubbock", List.of(closed, upcoming, open), c,
                Map.of(open.id(), 142), false, NOW);
        assertThat(o.stage()).isEqualTo(PreorderStatus.OPEN);
        assertThat(o.window()).isEqualTo(open);
        assertThat(o.count()).isEqualTo(142);
        assertThat(o.countdownTarget()).isEqualTo(open.details().closesAt());
        assertThat(o.doorFee()).isEqualTo(new Money(799, "USD"));
        assertThat(o.pickupFee()).isEqualTo(new Money(0, "USD"));

        Resolution onlyClosed = resolver.resolve("US", "TX", "Lubbock", List.of(closed), c, Map.of(), false, NOW);
        assertThat(onlyClosed.stage()).isEqualTo(PreorderStatus.CLOSED);
        assertThat(onlyClosed.countdownTarget()).isEqualTo(closed.details().deliversAt());
    }

    @Test
    void offWhenTheSwitchTheAreaOrTheWindowIsOff() {
        PreorderWindow open = window("US/TX/Lubbock", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(5)),
                NOW.plus(Duration.ofDays(7)));
        Resolution switchOff = resolver.resolve("US", "TX", "Lubbock", List.of(open),
                coverage(false, area("US/TX/Lubbock", true)), Map.of(), false, NOW);
        assertThat(switchOff.on()).isFalse();
        assertThat(switchOff.window()).isEqualTo(open);
        assertThat(switchOff.fallback()).isEqualTo(Fallback.SHIP);

        Resolution areaOff = resolver.resolve("US", "TX", "Lubbock", List.of(open),
                coverage(true, area("US/TX", true), area("US/TX/Lubbock", false)), Map.of(), false, NOW);
        assertThat(areaOff.on()).isFalse();

        Resolution paused = resolver.resolve("US", "TX", "Lubbock", List.of(open.pause(NOW)),
                coverage(true, area("US/TX/Lubbock", true)), Map.of(), false, NOW);
        assertThat(paused.on()).isFalse();
        assertThat(paused.window()).as("a paused window has no open/upcoming/closed stage").isNull();

        Resolution nowhere = resolver.resolve("US", "GA", "Atlanta", List.of(open),
                coverage(true, area("US/TX/Lubbock", true)), Map.of(), false, NOW);
        assertThat(nowhere.window()).isNull();
        assertThat(nowhere.on()).isFalse();

        Resolution metro = resolver.resolve("US", "TX", "Lubbock", List.of(open),
                coverage(true, area("US/TX/Lubbock", true)), Map.of(), true, NOW);
        assertThat(metro.metro()).isTrue();
        assertThat(metro.on()).isFalse();
        assertThat(metro.window()).isNull();
    }

    @Test
    void openListHasOpenThenUpcomingWindowsWithAnAreaOn() {
        PreorderWindow open = named("Lubbock", "US/TX/Lubbock", NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(5)));
        PreorderWindow upcomingA = named("Amarillo", "US/TX/Amarillo", NOW.plus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(5)));
        PreorderWindow offArea = named("Laredo", "US/TX/Laredo", NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(5)));
        PreorderWindow regionWide = named("All Texas", "US/TX", NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(5)));
        PreorderWindow closed = named("Old", "US/TX/Lubbock", NOW.minus(Duration.ofDays(10)),
                NOW.minus(Duration.ofDays(5)));
        Coverage c = coverage(true, area("US/TX/Lubbock", true), area("US/TX/Amarillo", true),
                area("US/TX/Laredo", false));
        assertThat(resolver.openList(List.of(upcomingA, closed, offArea, open, regionWide), c, NOW))
                .extracting(w -> w.details().name()).containsExactly("Lubbock", "Amarillo");
        assertThat(resolver.openList(List.of(open), coverage(false, area("US/TX/Lubbock", true)), NOW)).isEmpty();
    }

    @Test
    void productsInTheWindowsCategoriesArePreorderUnlessTheyShipNow() {
        PreorderWindow open = window("US/TX/Lubbock", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(5)),
                NOW.plus(Duration.ofDays(7)));
        Resolution r = resolver.resolve("US", "TX", "Lubbock", List.of(open),
                coverage(true, area("US/TX/Lubbock", true)), Map.of(), false, NOW);
        Eligibility egusi = resolver.eligibility(r, new ProductFacts("A1", "pantry", false, true));
        assertThat(egusi.preorder()).isTrue();
        assertThat(egusi.stage()).isEqualTo(PreorderStatus.OPEN);
        assertThat(resolver.eligibility(r, new ProductFacts("A2", "Pantry", true, true)).preorder()).isFalse();
        assertThat(resolver.eligibility(r, new ProductFacts("A3", "Fabric", false, true)).preorder()).isFalse();
        assertThat(resolver.eligibility(r, new ProductFacts("A4", "Pantry", false, false)).preorder()).isFalse();
    }

    @Test
    void aCatalogPathMatchesAnyOfItsLevels() {
        PreorderWindow open = window("US/TX/Lubbock", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(5)),
                NOW.plus(Duration.ofDays(7)));
        Resolution r = resolver.resolve("US", "TX", "Lubbock", List.of(open),
                coverage(true, area("US/TX/Lubbock", true)), Map.of(), false, NOW);
        assertThat(resolver.eligibility(r, new ProductFacts("A5", "Groceries › Pantry › Grains & rice", false, true))
                .preorder()).as("under Pantry").isTrue();
        assertThat(resolver.eligibility(r, new ProductFacts("A6", "Fashion › Fabrics › Ankara & wax prints", false,
                true)).preorder()).isFalse();
        assertThat(resolver.eligibility(r, new ProductFacts("A7", null, false, true)).preorder()).isFalse();
    }

    private static PreorderWindow window(String target, Instant opens, Instant closes, Instant delivers) {
        return Preorders.published(Preorders.withDates(Preorders.details(List.of(target)), opens, closes,
                LocalDate.ofInstant(delivers, Preorders.CHICAGO)));
    }

    private static PreorderWindow named(String name, String target, Instant opens, Instant closes) {
        WindowDetails d = Preorders.withDates(Preorders.details(List.of(target)), opens, closes,
                LocalDate.ofInstant(closes.plus(Duration.ofDays(2)), Preorders.CHICAGO));
        WindowDetails n = new WindowDetails(name, d.targets(), d.opensAt(), d.closesAt(), d.deliversOn(),
                d.timeZone(), d.ribbon(), d.headline(), d.smsCopy(), d.categories(), true, false, null, null, true,
                null, null, null, null, null);
        return new PreorderWindow(UUID.randomUUID(), "X-1", n, Map.of(CreativeSlot.HERO, Preorders.HERO), false,
                true, null, NOW, NOW, 0);
    }
}
