package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GtinTest {

    @Test
    void acceptsValidCodesOfEveryLength() {
        assertThat(Gtin.isValid("96385074")).isTrue();
        assertThat(Gtin.isValid("036000291452")).isTrue();
        assertThat(Gtin.isValid("4006381333931")).isTrue();
        assertThat(Gtin.isValid("10036000291459")).isTrue();
    }

    @Test
    void rejectsWrongCheckDigitAndSuggestsFix() {
        assertThat(Gtin.isValid("4006381333932")).isFalse();
        assertThat(Gtin.suggestCorrection("4006381333932")).isEqualTo("4006381333931");
    }

    @Test
    void stripsNonDigitsAndRecognisesIsbn() {
        Gtin isbn = Gtin.of("978-0-306-40615-7");
        assertThat(isbn.value()).isEqualTo("9780306406157");
        assertThat(isbn.isIsbn13()).isTrue();
    }

    @Test
    void refusesInvalidConstruction() {
        assertThatThrownBy(() -> Gtin.of("12345")).isInstanceOf(IllegalArgumentException.class);
    }
}
