package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionDeviceTest {

    @Test
    void userAgentIsCutToTheColumnWidthAndTheAddressIsOnlyKeptAsAHash() {
        SessionDevice d = SessionDevice.of("  Mozilla/5.0 " + "x".repeat(400), " 203.0.113.9 ");
        assertThat(d.userAgent()).hasSize(SessionDevice.MAX_USER_AGENT).startsWith("Mozilla/5.0 x");
        assertThat(d.ipHash()).isEqualTo(SecretHashing.sha256Hex("203.0.113.9")).hasSize(64);
    }

    @Test
    void nothingReportedMeansNoDevice() {
        assertThat(SessionDevice.of(null, null)).isNull();
        assertThat(SessionDevice.of(" ", "")).isNull();
        assertThat(SessionDevice.stored(null, null)).isNull();
        assertThat(SessionDevice.of("curl/8", null)).isEqualTo(new SessionDevice("curl/8", null));
    }
}
