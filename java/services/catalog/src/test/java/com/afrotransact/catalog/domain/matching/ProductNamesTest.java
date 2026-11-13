package com.afrotransact.catalog.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductNamesTest {

    @Test
    void foldsCaseAccentsAndPunctuation() {
        assertThat(ProductNames.normalise("  Crème-Fraîche, 200ML! ")).isEqualTo("creme fraiche 200ml");
    }

    @Test
    void dropsFillerAndPlurals() {
        assertThat(ProductNames.tokens("The Sweet Plantains and Yams")).containsExactly("sweet", "plantain", "yam");
    }

    @Test
    void cutsTheBrandOutWhereItsWordsAppearInARow() {
        assertThat(ProductNames.withoutBrand("Tilda Basmati Rice", "tilda")).isEqualTo("basmati rice");
        assertThat(ProductNames.withoutBrand("Royal Tilda Basmati", "Royal Tilda")).isEqualTo("basmati");
        assertThat(ProductNames.mentions("tilda basmati rice", "Tilda")).isTrue();
        assertThat(ProductNames.mentions("matilda basmati rice", "Tilda")).isFalse();
    }

    @Test
    void allowsOneTypoInLongerWordsOnly() {
        assertThat(ProductNames.sameWord("basmati", "basmatti")).isTrue();
        assertThat(ProductNames.sameWord("basmati", "basmoti")).isTrue();
        assertThat(ProductNames.sameWord("rice", "mice")).isFalse();
        assertThat(ProductNames.sameWord("basmati", "jasmine")).isFalse();
    }
}
