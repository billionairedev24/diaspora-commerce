package com.afrotransact.orders.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class HmacPayLinkSignerTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-12T18:00:00Z");
    private final HmacPayLinkSigner signer = new HmacPayLinkSigner("unit-test-pay-link-key");

    @Test
    void aTokenVerifiesForItsOwnOrderOnlyAndCarriesItsExpiry() {
        String token = signer.sign("order-1", "AT-2026-AAAAAA", EXPIRES);

        assertThat(token).matches("1\\.[0-9a-z]+\\.[A-Za-z0-9_-]{22}");
        assertThat(signer.sign("order-1", "AT-2026-AAAAAA", EXPIRES)).isEqualTo(token);
        assertThat(signer.verify("order-1", "AT-2026-AAAAAA", token)).contains(EXPIRES);
        assertThat(signer.verify("order-2", "AT-2026-AAAAAA", token)).isEmpty();
        assertThat(signer.verify("order-1", "AT-2026-BBBBBB", token)).isEmpty();
    }

    @Test
    void forgedOrMalformedTokensAreRefused() {
        String token = signer.sign("order-1", "AT-2026-AAAAAA", EXPIRES);
        String[] parts = token.split("\\.");
        String laterExpiry = parts[0] + "." + Long.toString(EXPIRES.getEpochSecond() + 86_400, 36) + "." + parts[2];

        assertThat(signer.verify("order-1", "AT-2026-AAAAAA", laterExpiry)).isEmpty();
        assertThat(signer.verify("order-1", "AT-2026-AAAAAA", token.substring(0, token.length() - 1) + "A")).isEmpty();
        for (String bad : new String[] {null, "", "1.x", "2." + parts[1] + "." + parts[2], "1.!!." + parts[2]}) {
            assertThat(signer.verify("order-1", "AT-2026-AAAAAA", bad)).as(String.valueOf(bad)).isEmpty();
        }
        assertThat(new HmacPayLinkSigner("another-pay-link-key").verify("order-1", "AT-2026-AAAAAA", token)).isEmpty();
        assertThatThrownBy(() -> new HmacPayLinkSigner("short")).isInstanceOf(IllegalStateException.class);
    }
}
