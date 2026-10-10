package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class DefaultTotpAuthenticatorTest {

    private final TotpAuthenticator totp = new DefaultTotpAuthenticator();
    /** RFC 6238 appendix B SHA-1 key "12345678901234567890". */
    private final String rfcSecret = Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesRfc6238TestVectors() {
        assertThat(totp.codeAt(rfcSecret, 59 / 30)).isEqualTo("287082");
        assertThat(totp.codeAt(rfcSecret, 1111111109L / 30)).isEqualTo("081804");
        assertThat(totp.codeAt(rfcSecret, 1234567890L / 30)).isEqualTo("005924");
    }

    @Test
    void acceptsAdjacentStepsButNotReplays() {
        Instant now = Instant.ofEpochSecond(1234567890L);
        long step = now.getEpochSecond() / 30;
        String code = totp.codeAt(rfcSecret, step);
        assertThat(totp.verify(rfcSecret, code, now, 0)).hasValue(step);
        assertThat(totp.verify(rfcSecret, code, now.plusSeconds(30), 0)).hasValue(step);
        assertThat(totp.verify(rfcSecret, code, now, step)).isEmpty();
        assertThat(totp.verify(rfcSecret, code, now.plusSeconds(120), 0)).isEmpty();
        assertThat(totp.verify(rfcSecret, "12345", now, 0)).isEmpty();
    }

    @Test
    void base32RoundTripsAndUriIsAuthenticatorFriendly() {
        String secret = totp.newSecret();
        assertThat(Base32.encode(Base32.decode(secret))).isEqualTo(secret);
        assertThat(totp.otpauthUri("AfroTransact", "ada@example.com", "ABC"))
                .isEqualTo("otpauth://totp/AfroTransact:ada%40example.com?secret=ABC&issuer=AfroTransact"
                        + "&algorithm=SHA1&digits=6&period=30");
    }
}
