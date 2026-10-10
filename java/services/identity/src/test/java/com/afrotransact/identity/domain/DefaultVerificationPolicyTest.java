package com.afrotransact.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultVerificationPolicyTest {

    private final VerificationPolicy policy = new DefaultVerificationPolicy();
    private final Instant now = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void codesAreSixDigits() {
        for (int i = 0; i < 50; i++) {
            assertThat(policy.newCode()).matches("\\d{6}");
        }
    }

    @Test
    void resendAtMostOncePer30Seconds() {
        assertThatThrownBy(() -> policy.checkCanIssue(List.of(now.minusSeconds(10)), now))
                .isInstanceOf(RateLimitExceeded.class)
                .satisfies(ex -> assertThat(((RateLimitExceeded) ex).retryAfter()).isEqualTo(Duration.ofSeconds(20)));
        assertThatCode(() -> policy.checkCanIssue(List.of(now.minusSeconds(31)), now)).doesNotThrowAnyException();
    }

    @Test
    void atMostFivePerHour() {
        List<Instant> five = List.of(now.minus(Duration.ofMinutes(50)), now.minus(Duration.ofMinutes(40)),
                now.minus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(10)));
        assertThatThrownBy(() -> policy.checkCanIssue(five, now))
                .isInstanceOf(RateLimitExceeded.class)
                .satisfies(ex -> assertThat(((RateLimitExceeded) ex).code()).isEqualTo("identity.too-many-codes"));
        List<Instant> oldOnes = List.of(now.minus(Duration.ofMinutes(70)), now.minus(Duration.ofMinutes(65)),
                now.minus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(10)));
        assertThatCode(() -> policy.checkCanIssue(oldOnes, now)).doesNotThrowAnyException();
    }

    @Test
    void codesExpireAfterTenMinutesAndLimitAttempts() {
        UUID id = UUID.randomUUID();
        VerificationCode code = new VerificationCode(id, UUID.randomUUID(), VerificationPurpose.EMAIL_VERIFICATION,
                "ada@example.com", policy.hash(id, "123456"), now, now.plus(VerificationPolicy.TIME_TO_LIVE), null, 0);
        assertThat(policy.check(code, "123456", now.plus(Duration.ofMinutes(9)))).isEqualTo(VerificationOutcome.VALID);
        assertThat(policy.check(code, "123456", now.plus(Duration.ofMinutes(10)))).isEqualTo(VerificationOutcome.EXPIRED);
        assertThat(policy.check(code, "654321", now)).isEqualTo(VerificationOutcome.WRONG_CODE);
        VerificationCode tired = code;
        for (int i = 0; i < VerificationPolicy.MAX_ATTEMPTS; i++) {
            tired = tired.failedAttempt();
        }
        assertThat(policy.check(tired, "123456", now)).isEqualTo(VerificationOutcome.TOO_MANY_ATTEMPTS);
        assertThat(policy.check(code.consume(now), "123456", now)).isEqualTo(VerificationOutcome.ALREADY_USED);
    }
}
