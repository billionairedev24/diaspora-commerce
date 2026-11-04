package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BusinessPageTest {

    @Test
    void theAddressComesFromTheBusinessName() {
        assertThat(BusinessPage.slugOf("Ade's Kitchen & Catering")).isEqualTo("ades-kitchen-catering");
        assertThat(BusinessPage.slugOf("  Café Ọlá  ")).isEqualTo("cafe-ola");
        assertThat(BusinessPage.slugOf("!!!")).isEqualTo("business");
        assertThat(BusinessPage.slugOf(null)).isEqualTo("business");
        assertThat(BusinessPage.slugOf("a".repeat(100))).hasSize(BusinessPage.MAX_SLUG);
    }
}
