package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.PasswordChanged;
import com.afrotransact.identity.application.IdentityEventPayloads.PasswordResetFederated;
import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.DefaultPasswordPolicy;
import com.afrotransact.identity.domain.DefaultSignInThrottlePolicy;
import com.afrotransact.identity.domain.DefaultVerificationPolicy;
import com.afrotransact.identity.domain.EffectiveAccess;
import com.afrotransact.identity.domain.FederatedIdentity;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.PrincipalType;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.identity.domain.SignInAuditEntry;
import com.afrotransact.identity.domain.ThrottleScope;
import com.afrotransact.identity.domain.ThrottleState;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationCode;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.FederatedIdentityRepository;
import com.afrotransact.identity.port.MfaRepository;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.PhoneVerificationRepository;
import com.afrotransact.identity.port.SessionStore;
import com.afrotransact.identity.port.SignInAuditLog;
import com.afrotransact.identity.port.SignInThrottleRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.identity.port.VerificationCodeRepository;
import com.afrotransact.identity.port.VerificationMessage;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * S300 password reset rules: the same answer for every email, one code for email and a verified phone, wrong /
 * expired / unknown codes answered alike and counted towards a lockout, the new password checked without burning the
 * code, and a completed reset signing the account out everywhere.
 */
class PasswordResetServiceImplTest {

    private static final Instant START = Instant.parse("2026-10-12T10:00:00Z");
    private static final String STRONG = "Plantain-Jollof-2026";
    private static final String IP = "203.0.113.7";

    private final MutableClock clock = new MutableClock(START);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final FederatedIdentityRepository federated = mock(FederatedIdentityRepository.class);
    private final PhoneVerificationRepository phones = mock(PhoneVerificationRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final SessionStore sessions = mock(SessionStore.class);
    private final MfaRepository mfa = mock(MfaRepository.class);
    private final AccessChangeLog changeLog = mock(AccessChangeLog.class);
    private final AccessProfileLoader profiles = mock(AccessProfileLoader.class);
    private final AccessPolicy accessPolicy = mock(AccessPolicy.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final FakeCodes codes = new FakeCodes();
    private final List<VerificationMessage> sent = new ArrayList<>();
    private final FakeThrottles throttles = new FakeThrottles();
    private final List<SignInAuditEntry> audit = new ArrayList<>();
    private final SignInAuditLog auditLog = new SignInAuditLog() {
        @Override
        public void append(SignInAuditEntry entry) {
            audit.add(entry);
        }

        @Override
        public List<SignInAuditEntry> recent(UUID userId, int limit) {
            return List.of();
        }
    };

    private final UserAccount ada = new UserAccount(UUID.randomUUID(), "Ada", "Okafor", "ada@example.com", true,
            "+17132490142", "{bcrypt}old", true, false, START, null, START, START, 3);

    private PasswordResetServiceImpl service() {
        CodeIssuer issuer = new CodeIssuer(codes, sent::add, new DefaultVerificationPolicy(), clock);
        return new PasswordResetServiceImpl(users, federated, phones, issuer, new DefaultPasswordPolicy(), hasher,
                sessions, mfa, throttles, DefaultSignInThrottlePolicy.defaults(), auditLog, changeLog, profiles,
                accessPolicy, events, clock);
    }

    private void known(UserAccount u, boolean staff) {
        when(users.findByEmail(u.email())).thenReturn(Optional.of(u));
        when(accessPolicy.resolve(any())).thenReturn(new EffectiveAccess(
                staff ? PrincipalType.STAFF : PrincipalType.CUSTOMER, Optional.empty(), Set.of(), Set.of(), staff));
        when(hasher.hash(anyString())).thenReturn("{bcrypt}new");
        when(users.update(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static PasswordResetRequest ask(String email) {
        return new PasswordResetRequest(email, IP, "JUnit", "gateway-bff", SignInAttempt.HOSTED);
    }

    private static PasswordResetCompletion confirm(String email, String code, String password) {
        return new PasswordResetCompletion(email, code, password, password, IP, "JUnit", "gateway-bff",
                SignInAttempt.HOSTED);
    }

    @Test
    void anAccountWithAPasswordGetsOneCodeByEmailAndByTextToItsVerifiedPhone() {
        known(ada, false);
        when(phones.verifiedPhone(ada.id())).thenReturn(Optional.of("+17132490142"));
        service().request(ask(" Ada@Example.com "));
        assertThat(sent).extracting(VerificationMessage::channel).containsExactly("EMAIL", "SMS");
        assertThat(sent).extracting(VerificationMessage::purpose).containsOnly(VerificationPurpose.PASSWORD_RESET);
        assertThat(sent.get(0).code()).isEqualTo(sent.get(1).code()).matches("\\d{6}");
        assertThat(sent.get(0).expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        // Hashed at rest: the stored row never holds the plain code.
        assertThat(codes.rows).hasSize(1).allSatisfy(c -> assertThat(c.codeHash()).doesNotContain(sent.get(0).code()));
        assertThat(audit).extracting(SignInAuditEntry::method, SignInAuditEntry::outcome, SignInAuditEntry::reason)
                .containsExactly(tuple("PASSWORD_RESET", "REQUESTED",
                        "code-sent-email-sms"));
    }

    @Test
    void anUnverifiedPhoneIsNeverTexted() {
        known(ada, false);
        when(phones.verifiedPhone(ada.id())).thenReturn(Optional.empty());
        service().request(ask("ada@example.com"));
        assertThat(sent).extracting(VerificationMessage::channel).containsExactly("EMAIL");
    }

    @Test
    void unknownEmailsAndSocialOnlyAccountsGetTheSameAnswerAndNoCode() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        service().request(ask("nobody@example.com"));
        UserAccount social = new UserAccount(UUID.randomUUID(), "Kofi", "Mensah", "kofi@example.com", true, null,
                null, true, false, START, null, START, START, 0);
        when(users.findByEmail("kofi@example.com")).thenReturn(Optional.of(social));
        when(federated.forUser(social.id())).thenReturn(List.of(
                new FederatedIdentity("google", "g-1", social.id(), "kofi@example.com", START)));
        service().request(ask("kofi@example.com"));

        assertThat(sent).isEmpty();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(IdentityEvents.PASSWORD_RESET_FEDERATED), eq(IdentityEvents.AGGREGATE),
                eq(social.id().toString()), payload.capture());
        assertThat(((PasswordResetFederated) payload.getValue()).providers()).containsExactly("Google");
        assertThat(audit).extracting(SignInAuditEntry::reason).containsExactly("no-account", "federated-only");
    }

    @Test
    void requestsAreLimitedPerEmailWithOrWithoutAnAccount() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        PasswordResetServiceImpl s = service();
        for (int i = 0; i < 5; i++) {
            s.request(new PasswordResetRequest("nobody@example.com", "198.51.100." + i, null, "api",
                    SignInAttempt.FIRST_PARTY));
        }
        assertThatThrownBy(() -> s.request(ask("nobody@example.com")))
                .isInstanceOfSatisfying(RateLimitExceeded.class, ex -> {
                    assertThat(ex.code()).isEqualTo(PasswordResetService.LOCKED);
                    assertThat(ex.retryAfter()).isPositive();
                });
        assertThat(audit).last().extracting(SignInAuditEntry::outcome).isEqualTo("BLOCKED");
        // A second, unrelated email from the same IP is not affected by the per-email limit.
        s.request(ask("someone-else@example.com"));
    }

    @Test
    void theHappyPathSetsThePasswordAndSignsOutEverywhere() {
        known(ada, false);
        when(sessions.revokeAll(ada.id(), Optional.empty())).thenReturn(2);
        when(sessions.endBrowserSessions(ada.id())).thenReturn(1);
        PasswordResetServiceImpl s = service();
        s.request(ask("ada@example.com"));

        PasswordResetCompleted done = s.complete(confirm("ada@example.com", sent.get(0).code(), STRONG));

        assertThat(done).isEqualTo(new PasswordResetCompleted("ada@example.com", 3, false));
        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).update(saved.capture());
        assertThat(saved.getValue().passwordHash()).isEqualTo("{bcrypt}new");
        verify(hasher).hash(STRONG);
        verify(mfa).deleteTrustedBrowsers(ada.id());
        verify(changeLog).append(ada.id().toString(), "password.reset", ada.id(), null, "password", null);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(IdentityEvents.PASSWORD_CHANGED), eq(IdentityEvents.AGGREGATE),
                eq(ada.id().toString()), payload.capture());
        PasswordChanged changed = (PasswordChanged) payload.getValue();
        assertThat(changed.method()).isEqualTo("RESET");
        assertThat(changed.sessionsRevoked()).isEqualTo(3);
        assertThat(changed.recipient().email()).isEqualTo("ada@example.com");
        assertThat(audit).last().extracting(SignInAuditEntry::outcome).isEqualTo("SUCCEEDED");

        // Single use: the same code cannot be used again.
        assertThatThrownBy(() -> s.complete(confirm("ada@example.com", sent.get(0).code(), STRONG)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo(PasswordResetService.INVALID_CODE));
    }

    @Test
    void staffResetStillRequiresTwoStepVerification() {
        UserAccount staff = new UserAccount(UUID.randomUUID(), "Tunde", "Bello", "tunde@afrotransact.com", true, null,
                "{bcrypt}old", true, false, START, null, START, START, 0);
        known(staff, true);
        PasswordResetServiceImpl s = service();
        s.request(ask("tunde@afrotransact.com"));
        PasswordResetCompleted done = s.complete(confirm("tunde@afrotransact.com", sent.get(0).code(), STRONG));
        assertThat(done.staff()).isTrue();
        // Trusted browsers are forgotten, so the second factor is asked at the next sign-in.
        verify(mfa).deleteTrustedBrowsers(staff.id());
    }

    @Test
    void wrongExpiredAndUnknownCodesFailAlikeAndChangeNothing() {
        known(ada, false);
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        PasswordResetServiceImpl s = service();
        s.request(ask("ada@example.com"));
        String right = sent.get(0).code();
        String wrong = right.equals("000000") ? "111111" : "000000";

        List<String> messages = new ArrayList<>();
        for (PasswordResetCompletion c : List.of(confirm("ada@example.com", wrong, STRONG),
                confirm("nobody@example.com", right, STRONG))) {
            assertThatThrownBy(() -> s.complete(c)).isInstanceOfSatisfying(IdentityRuleViolation.class, ex -> {
                assertThat(ex.code()).isEqualTo(PasswordResetService.INVALID_CODE);
                messages.add(ex.fieldErrors().get("code"));
            });
        }
        clock.now = START.plus(Duration.ofMinutes(11));
        assertThatThrownBy(() -> s.complete(confirm("ada@example.com", right, STRONG)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo(PasswordResetService.INVALID_CODE);
                    messages.add(ex.fieldErrors().get("code"));
                });
        assertThat(messages).hasSize(3).containsOnly(messages.get(0));
        verify(users, never()).update(any());
        verify(sessions, never()).revokeAll(any(), any());
        assertThat(audit).filteredOn(e -> "FAILED".equals(e.outcome())).extracting(SignInAuditEntry::reason)
                .containsExactly("invalid-code", "no-account", "code-expired");
    }

    @Test
    void repeatedWrongCodesLockTheEmailOut() {
        known(ada, false);
        PasswordResetServiceImpl s = service();
        s.request(ask("ada@example.com"));
        String wrong = sent.get(0).code().equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> s.complete(confirm("ada@example.com", wrong, STRONG)))
                    .isInstanceOf(IdentityRuleViolation.class);
        }
        assertThatThrownBy(() -> s.complete(confirm("ada@example.com", wrong, STRONG)))
                .isInstanceOfSatisfying(RateLimitExceeded.class,
                        ex -> assertThat(ex.code()).isEqualTo(PasswordResetService.LOCKED));
        // Even the right code is refused while locked.
        assertThatThrownBy(() -> s.complete(confirm("ada@example.com", sent.get(0).code(), STRONG)))
                .isInstanceOf(RateLimitExceeded.class);
        assertThat(audit).extracting(SignInAuditEntry::outcome).contains("LOCKED", "BLOCKED");
        verify(users, never()).update(any());
    }

    @Test
    void aRejectedNewPasswordDoesNotUseTheCode() {
        known(ada, false);
        PasswordResetServiceImpl s = service();
        s.request(ask("ada@example.com"));
        String code = sent.get(0).code();

        assertThatThrownBy(() -> s.complete(confirm("ada@example.com", code, "short")))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo(PasswordResetService.INVALID_PASSWORD);
                    assertThat(ex.fieldErrors()).containsKey("newPassword");
                });
        // Contains the person's name: only checked once the code is right, and the code stays valid.
        assertThatThrownBy(() -> s.complete(confirm("ada@example.com", code, "Okafor-Jollof-2026")))
                .isInstanceOfSatisfying(IdentityRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo(PasswordResetService.INVALID_PASSWORD));
        assertThat(s.complete(confirm("ada@example.com", code, STRONG)).email()).isEqualTo("ada@example.com");
    }

    /** In-memory verification codes. */
    private static final class FakeCodes implements VerificationCodeRepository {

        final List<VerificationCode> rows = new ArrayList<>();

        @Override
        public void insert(VerificationCode code) {
            rows.add(code);
        }

        @Override
        public void update(VerificationCode code) {
            rows.replaceAll(c -> c.id().equals(code.id()) ? code : c);
        }

        @Override
        public Optional<VerificationCode> latest(UUID userId, VerificationPurpose purpose) {
            return rows.stream().filter(c -> c.userId().equals(userId) && c.purpose() == purpose && !c.consumed())
                    .reduce((a, b) -> b);
        }

        @Override
        public List<Instant> issuedSince(UUID userId, VerificationPurpose purpose, Instant since) {
            return rows.stream().filter(c -> c.userId().equals(userId) && c.purpose() == purpose
                    && c.createdAt().isAfter(since)).map(VerificationCode::createdAt).toList();
        }
    }

    /** In-memory throttle counters. */
    private static final class FakeThrottles implements SignInThrottleRepository {

        private final Map<String, ThrottleState> rows = new HashMap<>();

        @Override
        public ThrottleState find(ThrottleScope scope, String key, Instant now) {
            return rows.getOrDefault(scope + "|" + key, ThrottleState.fresh(scope, key, now));
        }

        @Override
        public ThrottleState lock(ThrottleScope scope, String key, Instant now) {
            return find(scope, key, now);
        }

        @Override
        public void save(ThrottleState state, Instant now) {
            rows.put(state.scope() + "|" + state.key(), state);
        }
    }

    /** A clock the test moves forward. */
    private static final class MutableClock extends Clock {

        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
