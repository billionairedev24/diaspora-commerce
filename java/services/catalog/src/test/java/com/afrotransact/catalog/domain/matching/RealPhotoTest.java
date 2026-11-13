package com.afrotransact.catalog.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RealPhotoTest {

    @Test
    void acceptsPhotographAddresses() {
        assertThat(RealPhoto.is("https://cdn.example.com/p/rice.jpg")).isTrue();
        assertThat(RealPhoto.is("/media/rice.webp")).isTrue();
    }

    @Test
    void refusesPlaceholdersVectorsIconsAndInlineImages() {
        assertThat(RealPhoto.is(null)).isFalse();
        assertThat(RealPhoto.is(" ")).isFalse();
        assertThat(RealPhoto.is("https://cdn.example.com/placeholder.png")).isFalse();
        assertThat(RealPhoto.is("https://cdn.example.com/logo.svg")).isFalse();
        assertThat(RealPhoto.is("https://cdn.example.com/favicon.ico")).isFalse();
        assertThat(RealPhoto.is("data:image/png;base64,AAAA")).isFalse();
        assertThat(RealPhoto.is("ftp://x/y.jpg")).isFalse();
    }
}
