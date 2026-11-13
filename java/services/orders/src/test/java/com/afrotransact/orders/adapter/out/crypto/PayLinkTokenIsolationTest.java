package com.afrotransact.orders.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

/** A pay-now token is bound to one order: it opens that order only, cannot be tampered with and carries its expiry. */
class PayLinkTokenIsolationTest {

    private static final String KEY = "test-key-test-key-test-key-1234";
    private final HmacPayLinkSigner signer = new HmacPayLinkSigner(KEY);
    private final Instant expiry = Instant.parse("2026-11-20T12:00:00Z").truncatedTo(ChronoUnit.SECONDS);

    @Test
    void aTokenOfOrderOneNeverOpensOrderTwo() {
        String token = signer.sign("order-1", "AT-2026-000001", expiry);

        assertThat(signer.verify("order-1", "AT-2026-000001", token)).contains(expiry);
        assertThat(signer.verify("order-2", "AT-2026-000002", token)).isEmpty();
        assertThat(signer.verify("order-1", "AT-2026-000002", token)).isEmpty();
        assertThat(signer.verify("order-2", "AT-2026-000001", token)).isEmpty();
    }

    @Test
    void aTokenWithAnotherExpiryOrAnotherKeyIsRejected() {
        String token = signer.sign("order-1", "AT-2026-000001", expiry);
        String[] parts = token.split("\\.");
        String longer = parts[0] + "." + Long.toString(expiry.plusSeconds(86_400).getEpochSecond(), 36) + "."
                + parts[2];

        assertThat(signer.verify("order-1", "AT-2026-000001", longer)).isEmpty();
        assertThat(new HmacPayLinkSigner("another-key-another-key-9999")
                .verify("order-1", "AT-2026-000001", token)).isEmpty();
    }

    @Test
    void emptyOversizedAndMalformedTokensAreRejected() {
        assertThat(signer.verify("order-1", "AT-1", null)).isEmpty();
        assertThat(signer.verify("order-1", "AT-1", "")).isEmpty();
        assertThat(signer.verify("order-1", "AT-1", "1.x.y")).isEmpty();
        assertThat(signer.verify("order-1", "AT-1", "1." + "a".repeat(300))).isEmpty();
    }
}
