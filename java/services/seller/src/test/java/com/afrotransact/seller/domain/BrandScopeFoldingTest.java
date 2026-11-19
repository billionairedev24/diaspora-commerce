package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Brand-gating names compare like the catalog brand registry: case, accents and punctuation do not matter (S630). */
class BrandScopeFoldingTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    @ParameterizedTest
    @CsvSource({"Hermès,hermes", "HERMES,hermes", "Levi's,levis", "Néstle,nestle", "Louis  Vuitton,louisvuitton",
            "H&M,hm", "Élan Vital,elanvital"})
    void foldsCaseAccentsAndPunctuation(String typed, String key) {
        assertThat(SellingScope.normaliseBrand(typed)).isEqualTo(key);
        assertThat(SellingScope.brand(typed).key()).isEqualTo(key);
        assertThat(SellingScope.validKey(SellingScopeType.BRAND, key)).isTrue();
    }

    @Test
    void aBrandInACategoryFoldsOnlyTheBrandHalf() {
        assertThat(SellingScope.brandInCategory("Hermès", "Bags").key()).isEqualTo("hermes|bags");
    }

    @Test
    void anAccentedListingBrandMatchesAnUnaccentedGatedBrandAndTheOtherWayRound() {
        SellingRule gated = new SellingRule(UUID.randomUUID(), SellingScope.brand("Hermes"), "Hermes", SellingLevel.APPLY,
                List.of(), "", "", null, null, Set.of(), false, 30, true, "STAFF", NOW, null, 0);
        for (String typed : List.of("Hermès", "HERMES", "hermes", "Hermès ")) {
            SellingStatus s = SellingEvaluator.evaluate(new SellingFacts("bags", typed, null, null, null, false),
                    List.of(gated), Map.of(), Map.of(), NOW);
            assertThat(s.level()).as(typed).isEqualTo(SellingLevel.APPLY);
        }
        SellingRule accented = new SellingRule(UUID.randomUUID(), SellingScope.brand("Hermès"), "Hermès",
                SellingLevel.APPLY, List.of(), "", "", null, null, Set.of(), false, 30, true, "STAFF", NOW, null, 0);
        assertThat(accented.scope()).isEqualTo(SellingScope.brand("hermes"));
    }

    @Test
    void aDifferentBrandIsNotCaught() {
        assertThat(SellingScope.brand("Hermann")).isNotEqualTo(SellingScope.brand("Hermès"));
    }
}
