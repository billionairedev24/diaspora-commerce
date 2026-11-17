package com.afrotransact.catalog.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class NameTokensTest {

    @Test
    void reducesTheNameToItsWords() {
        assertThat(NameTokens.of("Ground Egusi")).containsExactly("ground", "egusi");
        assertThat(NameTokens.of("  Ground  EGUSI (Melon Seeds) ")).containsExactly("ground", "egusi", "melon", "seeds");
    }

    @Test
    void dropsSizesPackWordsAndFillerButKeepsBrandsAndAccentsPlain() {
        assertThat(NameTokens.of("Tilda Basmati Rice 5kg")).containsExactly("tilda", "basmati", "rice");
        assertThat(NameTokens.of("Maggi cubes pack of 24 x 4g")).containsExactly("maggi", "cubes");
        assertThat(NameTokens.of("Crème fraîche")).containsExactly("creme", "fraiche");
    }

    @Test
    void capsTheWordCountAndHandlesNothingSearchable() {
        assertThat(NameTokens.of("aa bb cc dd ee ff gg hh")).hasSize(6);
        assertThat(NameTokens.of("5 kg")).isEmpty();
        assertThat(NameTokens.of(null)).isEqualTo(List.of());
    }

    @Test
    void neverLetsSqlWildcardsThrough() {
        assertThat(NameTokens.of("50%_off; drop table")).containsExactly("off", "drop", "table");
    }
}
