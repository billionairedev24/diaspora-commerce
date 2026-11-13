package com.afrotransact.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SignInFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration WINDOW = Duration.ofMinutes(30);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAt(Instant authTime) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("cust-1").issuedAt(NOW.minusSeconds(60))
                .expiresAt(NOW.plusSeconds(240));
        if (authTime != null) {
            jwt.claim(SignInFreshness.AUTH_TIME, authTime);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
    }

    @Test
    void aRecentSignInPasses() {
        signedInAt(NOW.minus(Duration.ofMinutes(29)));
        assertThat(SignInFreshness.isFresh(WINDOW, CLOCK)).isTrue();
        assertThatCode(() -> SignInFreshness.require(WINDOW, CLOCK, "remove a payment method"))
                .doesNotThrowAnyException();
    }

    @Test
    void anOldSignInNeedsAFreshOneWithTheStepUpCode() {
        signedInAt(NOW.minus(Duration.ofDays(60)));
        assertThatThrownBy(() -> SignInFreshness.require(WINDOW, CLOCK, "remove a payment method"))
                .isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("sign in again to remove a payment method");
    }

    @Test
    void aTokenWithoutAuthTimeCountsAsStale() {
        signedInAt(null);
        assertThat(SignInFreshness.isFresh(WINDOW, CLOCK)).isFalse();
    }

    @Test
    void callsThatAreNotJwtAuthenticatedPass() {
        assertThat(SignInFreshness.isFresh(WINDOW, CLOCK)).isTrue();
    }
}
