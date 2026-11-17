package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LogoUrlRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://media.afrotransact.com/storefront/a1.webp",
            "https://pub-1234.r2.dev/storefront/a1.png?v=2",
            "https://media-dev.afrotransact.com/x.jpg",
            "http://localhost:9000/media/storefront/a1.webp",
            "http://localhost:8207/v1/assets/a1/content",
            "http://127.0.0.1:9000/media/a1.png",
            "http://media.localhost:9000/a1.png",
            "http://192.168.1.20:9000/media/a1.png",
            "http://host.docker.internal:9000/media/a1.png"
    })
    void aLogoOnTheMediaHostPassesInLocalAndProductionStyle(String url) {
        assertThat(LogoUrlRules.accepts(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://media.afrotransact.com/a1.png",
            "http://8.8.8.8/a1.png",
            "ftp://localhost/a1.png",
            "javascript:alert(1)",
            "https://",
            "https://user:pw@media.afrotransact.com/a.png",
            "https://media.afrotransact.com/a b.png",
            "not a url",
            "/media/a1.png"
    })
    void aBadAddressIsRefused(String url) {
        assertThat(LogoUrlRules.accepts(url)).isFalse();
    }

    @Test
    void aVeryLongAddressIsRefused() {
        assertThat(LogoUrlRules.accepts("https://media.afrotransact.com/" + "a".repeat(2100))).isFalse();
    }

    @Test
    void colourIsNormalisedFromAnyReasonableSpelling() {
        assertThat(BrandingRules.normaliseColour("#1A7F4B")).isEqualTo("#1a7f4b");
        assertThat(BrandingRules.normaliseColour("1a7f4b")).isEqualTo("#1a7f4b");
        assertThat(BrandingRules.normaliseColour("#0F8")).isEqualTo("#00ff88");
        assertThat(BrandingRules.normaliseColour("  fa0 ")).isEqualTo("#ffaa00");
        assertThat(BrandingRules.normaliseColour("green")).isNull();
        assertThat(BrandingRules.normaliseColour("#12345")).isNull();
        assertThat(BrandingRules.normaliseColour("  ")).isNull();
        assertThat(BrandingRules.normaliseColour(null)).isNull();
    }
}
