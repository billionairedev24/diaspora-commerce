package com.afrotransact.catalog.domain.brand;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The "did you mean" check works on comparison keys, so case, accents and punctuation are already gone. */
class BrandSimilarityTest {

    private static boolean near(String a, String b) {
        return BrandSimilarity.near(BrandNames.normalise(a), BrandNames.normalise(b));
    }

    @Test
    void oneTypingMistakeIsNear() {
        assertThat(near("Adidass", "Adidas")).isTrue();
        assertThat(near("Adiads", "Adidas")).isTrue();
        assertThat(near("Samsung", "Samsnug")).isTrue();
        assertThat(near("Nikee", "Nike")).isTrue();
        assertThat(near("Toyota", "Toyata")).isTrue();
    }

    @Test
    void twoMistakesCountOnlyForLongNames() {
        assertThat(near("Johnson Baby", "Jonhsno Baby")).isTrue();
        assertThat(near("Nike", "Nuke Co")).isFalse();
        assertThat(near("Gucci", "Gucco")).isTrue();
        assertThat(near("Puma", "Pumb")).isTrue();
        assertThat(near("Puma", "Pomb")).isFalse();
    }

    @Test
    void theSameNameWithAFewMoreLettersIsNear() {
        assertThat(near("Nike Air", "Nike")).isTrue();
        assertThat(near("Nike", "Nike Sport")).isTrue();
        assertThat(near("Dove", "Dovetail Interiors")).isFalse();
    }

    @Test
    void identicalKeysAreAnExactMatchNotASuggestion() {
        assertThat(near("Levi's", "LEVIS")).isFalse();
        assertThat(BrandSimilarity.near("nike", "nike")).isFalse();
    }

    @Test
    void shortOrUnrelatedNamesAreNeverNear() {
        assertThat(near("KFC", "KFG")).isFalse();
        assertThat(near("H&M", "HP")).isFalse();
        assertThat(near("Apple", "Orange")).isFalse();
        assertThat(BrandSimilarity.near(null, "nike")).isFalse();
    }

    @Test
    void anAdjacentSwapIsOneMistake() {
        assertThat(BrandSimilarity.distance("adidas", "adiads", 2)).isEqualTo(1);
        assertThat(BrandSimilarity.distance("nike", "nike", 2)).isZero();
        assertThat(BrandSimilarity.distance("abcdefgh", "zzzzzzzz", 2)).isEqualTo(3);
    }
}
