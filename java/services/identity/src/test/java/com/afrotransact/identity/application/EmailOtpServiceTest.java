package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.DefaultVerificationPolicy;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationCode;
import com.afrotransact.identity.domain.VerificationPolicy;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.VerificationCodeRepository;
import com.afrotransact.identity.port.VerificationMessage;
import org.junit.jupiter.api.Test;

/** The emailed second-factor code: TTL, attempts, throttling, single use and hashing at rest. */
class EmailOtpServiceTest {

    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final List<VerificationCode> stored = new ArrayList<>();
    private final List<VerificationMessage> sent = new ArrayList<>();
    private final UserAccount ada = new UserAccount(UUID.randomUUID(), "Ada", "Okafor", "ada@example.com", true, null,
            "{x}h", true, false, START, null, START, START, 0);

    private final VerificationCodeRepository repo = new VerificationCodeRepository() {
        @Override
        public void insert(VerificationCode code) {
            stored.add(code);
        }

        @Override
        public void update(VerificationCode code) {
            stored.replaceAll(c -> c.id().equals(code.id()) ? code : c);
        }

        @Override
        public Optional<VerificationCode> latest(UUID userId, VerificationPurpose purpose) {
            return stored.stream().filter(c -> c.purpose() == purpose && c.userId().equals(userId))
                    .reduce((a, b) -> b);
        }

        @Override
        public List<Instant> issuedSince(UUID userId, VerificationPurpose purpose, Instant since) {
            return stored.stream().filter(c -> c.purpose() == purpose && c.createdAt().isAfter(since))
                    .map(VerificationCode::createdAt).toList();
        }
    };

    private final CodeIssuer issuer = new CodeIssuer(repo, sent::add, new DefaultVerificationPolicy(), clock);

    private String issue() {
        issuer.issue(ada, VerificationPurpose.SIGN_IN_EMAIL, "EMAIL", ada.email());
        return sent.get(sent.size() - 1).code();
    }

    @Test
    void sendsASixDigitCodeToTheAccountEmailThatExpiresInTenMinutes() {
        Instant expires = issuer.issue(ada, VerificationPurpose.SIGN_IN_EMAIL, "EMAIL", ada.email());

        VerificationMessage m = sent.get(0);
        assertThat(m.channel()).isEqualTo("EMAIL");
        assertThat(m.destination()).isEqualTo("ada@example.com");
        assertThat(m.code()).matches("\\d{6}");
        assertThat(expires).isEqualTo(START.plus(VerificationPolicy.TIME_TO_LIVE)).isEqualTo(START.plusSeconds(600));
    }

    @Test
    void theCodeIsHashedAtRestAndNeverStoredInTheClear() {
        String code = issue();

        assertThat(stored.get(0).codeHash()).doesNotContain(code).hasSize(64);
    }

    @Test
    void aCodeWorksOnceOnly() {
        String code = issue();

        issuer.consume(ada.id(), VerificationPurpose.SIGN_IN_EMAIL, code);

        assertThatThrownBy(() -> issuer.consume(ada.id(), VerificationPurpose.SIGN_IN_EMAIL, code))
                .isInstanceOf(IdentityRuleViolation.class).hasMessageContaining("already used");
    }

    @Test
    void aCodeExpiresAfterTheTimeToLive() {
        String code = issue();
        clock.advance(VerificationPolicy.TIME_TO_LIVE.plusSeconds(1));

        assertThatThrownBy(() -> issuer.consume(ada.id(), VerificationPurpose.SIGN_IN_EMAIL, code))
                .isInstanceOf(IdentityRuleViolation.class).hasMessageContaining("expired");
    }

    @Test
    void tooManyWrongGuessesBurnTheCodeEvenForTheRightOne() {
        String code = issue();
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < VerificationPolicy.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> issuer.consume(ada.id(), VerificationPurpose.SIGN_IN_EMAIL, wrong))
                    .isInstanceOf(IdentityRuleViolation.class);
        }

        assertThatThrownBy(() -> issuer.consume(ada.id(), VerificationPurpose.SIGN_IN_EMAIL, code))
                .isInstanceOf(IdentityRuleViolation.class).hasMessageContaining("Too many");
    }

    @Test
    void resendIsThrottledPerThirtySecondsAndFivePerHour() {
        issue();
        assertThatThrownBy(this::issue).isInstanceOf(RateLimitExceeded.class);

        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(31));
            issue();
        }
        clock.advance(Duration.ofSeconds(31));
        assertThatThrownBy(this::issue).isInstanceOf(RateLimitExceeded.class);
        assertThat(sent).hasSize(5);
    }

    @Test
    void aCodeForAnotherPurposeCannotBeReplayedAsASignInCode() {
        issuer.issue(ada, VerificationPurpose.EMAIL_OTP_ENROLMENT, "EMAIL", ada.email());
        String enrolmentCode = sent.get(0).code();

        assertThatThrownBy(() -> issuer.consume(ada.id(), VerificationPurpose.SIGN_IN_EMAIL, enrolmentCode))
                .isInstanceOf(IdentityRuleViolation.class);
    }
}
