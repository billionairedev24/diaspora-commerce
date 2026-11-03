package com.afrotransact.identity.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A rotated client secret: the new one always works, the old one only during the grace period. */
class RotatingSecretEncoderTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private static PasswordEncoder encoderAt(Instant now) {
        PasswordEncoder bcrypt = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder()));
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(),
                RotatingSecretEncoder.ID, new RotatingSecretEncoder(bcrypt, Clock.fixed(now, ZoneOffset.UTC))));
    }

    @Test
    void bothSecretsWorkInTheGracePeriodAndOnlyTheNewOneAfter() {
        PasswordEncoder hashing = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder()));
        String current = hashing.encode("new-secret");
        String previous = hashing.encode("old-secret");
        String stored = RotatingSecretEncoder.compose(NOW.plusSeconds(3600), current, previous);

        PasswordEncoder during = encoderAt(NOW);
        assertThat(during.matches("new-secret", stored)).isTrue();
        assertThat(during.matches("old-secret", stored)).isTrue();
        assertThat(during.matches("guess", stored)).isFalse();

        PasswordEncoder after = encoderAt(NOW.plusSeconds(3601));
        assertThat(after.matches("new-secret", stored)).isTrue();
        assertThat(after.matches("old-secret", stored)).isFalse();
    }

    @Test
    void aMalformedPairMatchesNothing() {
        PasswordEncoder encoder = encoderAt(NOW);
        assertThat(encoder.matches("x", "{rotating}garbage")).isFalse();
        assertThat(encoder.matches("x", "{rotating}notanumber|{bcrypt}a|{bcrypt}b")).isFalse();
    }
}
