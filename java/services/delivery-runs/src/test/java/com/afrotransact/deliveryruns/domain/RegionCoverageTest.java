package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.List;

import com.afrotransact.deliveryruns.domain.RegionCoverage.CityCoverage;
import org.junit.jupiter.api.Test;

/** Editing one region's coverage in place: add, remove and switch cities and the "Every city" row. */
class RegionCoverageTest {

    private static final AreaKey TX = AreaKey.parse("US/TX");

    private static CoverageArea row(String key, boolean on, long version) {
        return new CoverageArea(AreaKey.parse(key), on, version);
    }

    private final Coverage coverage = new Coverage(PreorderSettings.DEFAULT, List.of(row("US/TX/Lubbock", true, 2),
            row("US/TX/El Paso", true, 0), row("US/TX/Laredo", false, 1), row("CA/ON/Toronto", true, 0)));

    @Test
    void aRegionEditAddsRemovesAndSwitchesRows() {
        RegionCoverage want = new RegionCoverage(TX, null, List.of(new CityCoverage("lubbock", true),
                new CityCoverage("El Paso", false), new CityCoverage("Abilene", true), new CityCoverage("Waco", false)));

        AreaChanges c = coverage.changes(want);

        assertThat(c.added()).extracting(a -> a.key().key(), CoverageArea::on)
                .containsExactly(tuple("US/TX/Abilene", true),
                        tuple("US/TX/Waco", false));
        assertThat(c.removed()).extracting(a -> a.key().key()).containsExactly("US/TX/Laredo");
        assertThat(c.switched()).extracting(a -> a.key().key(), CoverageArea::on, CoverageArea::version)
                .containsExactly(tuple("US/TX/El Paso", false, 0L));

        Coverage after = coverage.apply(c);
        assertThat(after.region(TX)).extracting(a -> a.key().city())
                .containsExactly("Abilene", "El Paso", "Lubbock", "Waco");
        assertThat(after.areaOn("US", "TX", "El Paso")).isFalse();
        assertThat(after.areaOn("US", "TX", "Abilene")).isTrue();
        assertThat(after.find(AreaKey.parse("US/TX/El Paso")).orElseThrow().version()).isEqualTo(1);
        assertThat(after.areaOn("CA", "ON", "Toronto")).as("other regions untouched").isTrue();
        assertThat(coverage.changes(new RegionCoverage(TX, null, List.of(new CityCoverage("Lubbock", true),
                new CityCoverage("El Paso", true), new CityCoverage("Laredo", false)))).empty()).isTrue();
    }

    @Test
    void theEveryCityRowCoversCitiesWithoutTheirOwnRow() {
        Coverage whole = coverage.apply(coverage.changes(new RegionCoverage(TX, true,
                List.of(new CityCoverage("Laredo", false)))));
        assertThat(whole.areaOn("US", "TX", "Midland")).isTrue();
        assertThat(whole.areaOn("US", "TX", "Laredo")).as("a city row overrides its region").isFalse();
        assertThat(whole.region(TX).get(0).key().isCity()).as("Every city first").isFalse();
    }

    @Test
    void theRegionVersionChangesWhenAnyRowIsAddedRemovedOrSwitched() {
        long v = coverage.regionVersion(TX);
        assertThat(coverage.regionVersion(AreaKey.parse("US/TX/Lubbock"))).isEqualTo(v);
        assertThat(new Coverage(PreorderSettings.DEFAULT, coverage.areas()).regionVersion(TX)).isEqualTo(v);
        List<CoverageArea> switched = new ArrayList<>(coverage.areas());
        switched.set(0, row("US/TX/Lubbock", true, 3));
        assertThat(new Coverage(PreorderSettings.DEFAULT, switched).regionVersion(TX)).isNotEqualTo(v);
        assertThat(coverage.apply(new AreaChanges(List.of(row("US/TX/Waco", true, 0)), List.of(), List.of()))
                .regionVersion(TX)).isNotEqualTo(v);
        assertThat(coverage.apply(new AreaChanges(List.of(), List.of(row("US/TX/Laredo", false, 1)), List.of()))
                .regionVersion(TX)).isNotEqualTo(v);
        assertThat(coverage.apply(new AreaChanges(List.of(row("CA/ON/Ottawa", true, 0)), List.of(), List.of()))
                .regionVersion(TX)).as("another region's change does not conflict").isEqualTo(v);
    }

    @Test
    void removingAPlaceALiveWindowSellsToStrandsIt() {
        List<AreaKey> live = List.of(AreaKey.parse("US/TX/Lubbock"), AreaKey.parse("CA/ON/Toronto"));
        Coverage withoutLubbock = coverage.apply(coverage.changes(new RegionCoverage(TX, null,
                List.of(new CityCoverage("El Paso", true)))));
        assertThat(coverage.stranded(withoutLubbock, live)).extracting(AreaKey::key).containsExactly("US/TX/Lubbock");

        Coverage lubbockOff = coverage.apply(coverage.changes(new RegionCoverage(TX, null,
                List.of(new CityCoverage("Lubbock", false)))));
        assertThat(coverage.stranded(lubbockOff, live)).as("switching off is allowed").isEmpty();

        Coverage everyCity = coverage.apply(coverage.changes(new RegionCoverage(TX, true, List.of())));
        assertThat(coverage.stranded(everyCity, live)).as("still covered by Every city").isEmpty();
    }

    @Test
    void citiesAreValidatedAndDeduplicated() {
        RegionCoverage dup = new RegionCoverage(TX, null, List.of(new CityCoverage("Waco", true),
                new CityCoverage(" waco ", false)));
        assertThat(dup.cities()).containsExactly(new CityCoverage("waco", false));
        assertThatThrownBy(() -> new RegionCoverage(AreaKey.parse("US/TX/Waco"), null, List.of()))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new RegionCoverage(TX, null, List.of(new CityCoverage(" ", true))))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> new RegionCoverage(TX, null, List.of(new CityCoverage("A/B", true))))
                .isInstanceOf(RuleViolation.class);
    }
}
