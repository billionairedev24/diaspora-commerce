package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.LoginTicket;
import com.afrotransact.identity.domain.MfaFactor;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.domain.SignInChallenge;
import com.afrotransact.identity.domain.SignInFailed;
import com.afrotransact.identity.domain.SignInMethod;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.FirstPartySignInStore;
import com.afrotransact.identity.port.PasskeyAssertions;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FirstPartySignInServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final ClientContext CTX = new ClientContext("203.0.113.7", "Mozilla/5.0");
    private static final Actor BFF = new Actor("gateway-bff", PrincipalType.SERVICE, Optional.empty(), Set.of(),
            Set.of("auth:first-party"));

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final SignInService signIn = mock(SignInService.class);
    private final SignInProtectionService protection = mock(SignInProtectionService.class);
    private final PasskeyAssertions passkeys = mock(PasskeyAssertions.class);
    private final FakeStore store = new FakeStore();
    private final UserAccount ada = new UserAccount(UUID.randomUUID(), "Ada", "Okafor", "ada@example.com", true,
            "+17132490142", "{bcrypt}hash", true, false, NOW, null, NOW, NOW, 0);
    private Actor caller = BFF;

    private FirstPartySignInServiceImpl service() {
        return new FirstPartySignInServiceImpl(new CurrentUser(() -> caller),
                new FirstPartySignInSettings(Set.of("gateway-bff")), users, hasher, signIn, protection, store,
                passkeys, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void onlyTheFirstPartyClientMayCall() {
        caller = new Actor(UUID.randomUUID().toString(), PrincipalType.CUSTOMER, Optional.empty(), Set.of(),
                Set.of("auth:first-party"));
        assertThatThrownBy(() -> service().password(new PasswordSignIn("ada@example.com", "pw", null, CTX)))
                .isInstanceOf(ForbiddenActionException.class);
        caller = new Actor("notifications-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("auth:first-party"));
        assertThatThrownBy(() -> service().passkeyOptions(CTX)).isInstanceOf(ForbiddenActionException.class);
        caller = new Actor("gateway-bff", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of("users:read"));
        assertThatThrownBy(() -> service().passkeyOptions(CTX)).isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void wrongPasswordAndUnknownEmailFailTheSameWayAndAreCounted() {
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(hasher.matches("wrong", "{bcrypt}hash")).thenReturn(false);
        when(hasher.hash(anyString())).thenReturn("{bcrypt}dummy");
        FirstPartySignInServiceImpl s = service();

        assertThatThrownBy(() -> s.password(new PasswordSignIn(" Ada@Example.com ", "wrong", null, CTX)))
                .isInstanceOf(SignInFailed.class).hasMessage("Email or password is incorrect");
        assertThatThrownBy(() -> s.password(new PasswordSignIn("nobody@example.com", "wrong", null, CTX)))
                .isInstanceOf(SignInFailed.class).hasMessage("Email or password is incorrect");

        ArgumentCaptor<SignInAttempt> failures = ArgumentCaptor.forClass(SignInAttempt.class);
        verify(protection, times(2)).recordFailure(failures.capture());
        assertThat(failures.getAllValues()).extracting(SignInAttempt::email)
                .containsExactly("ada@example.com", "nobody@example.com");
        assertThat(failures.getAllValues()).extracting(SignInAttempt::ip).containsOnly("203.0.113.7");
        verify(hasher).matches("wrong", "{bcrypt}dummy");
        assertThat(store.tickets).isEmpty();
    }

    @Test
    void passwordWithEnrolledFactorReturnsAChallengeNotATicket() {
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(hasher.matches("right", "{bcrypt}hash")).thenReturn(true);
        when(signIn.challenge(ada.id(), Optional.empty(), false))
                .thenReturn(new SecondFactorChallenge(true, true, false, null, true));

        SignInStep step = service().password(new PasswordSignIn("ada@example.com", "right", null, CTX));

        assertThat(step.status()).isEqualTo(SignInStep.Status.MFA_REQUIRED);
        assertThat(step.loginTicket()).isNull();
        assertThat(step.factors().totp()).isTrue();
        SignInChallenge c = store.challenges.get(step.challengeId());
        assertThat(c.userId()).isEqualTo(ada.id());
        assertThat(c.clientId()).isEqualTo("gateway-bff");
        verify(protection, never()).recordSuccess(any());
    }

    @Test
    void passwordWithoutSecondFactorIssuesAHashedSingleUseTicket() {
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(hasher.matches("right", "{bcrypt}hash")).thenReturn(true);
        when(signIn.challenge(ada.id(), Optional.of("device-token"), false))
                .thenReturn(SecondFactorChallenge.none());
        FirstPartySignInServiceImpl s = service();

        SignInStep step = s.password(new PasswordSignIn("ada@example.com", "right", "device-token", CTX));

        assertThat(step.status()).isEqualTo(SignInStep.Status.AUTHENTICATED);
        assertThat(step.loginTicketExpiresAt()).isEqualTo(NOW.plus(LoginTicket.TTL));
        LoginTicket stored = store.tickets.get(SecretHashing.sha256Hex(step.loginTicket()));
        assertThat(stored.method()).isEqualTo(SignInMethod.PASSWORD);
        assertThat(store.tickets).doesNotContainKey(step.loginTicket());
        verify(protection).recordSuccess(any());

        assertThat(s.redeemLoginTicket(step.loginTicket(), "storefront")).isEmpty();
        assertThat(s.redeemLoginTicket(step.loginTicket(), "gateway-bff")).as("burned by the wrong client").isEmpty();
    }

    @Test
    void ticketRedeemsOnceForItsClient() {
        when(users.findByEmail("ada@example.com")).thenReturn(Optional.of(ada));
        when(hasher.matches("right", "{bcrypt}hash")).thenReturn(true);
        when(signIn.challenge(eq(ada.id()), any(), eq(false))).thenReturn(SecondFactorChallenge.none());
        FirstPartySignInServiceImpl s = service();
        String ticket = s.password(new PasswordSignIn("ada@example.com", "right", null, CTX)).loginTicket();

        assertThat(s.redeemLoginTicket(ticket, "gateway-bff")).map(RedeemedLoginTicket::userId).contains(ada.id());
        assertThat(s.redeemLoginTicket(ticket, "gateway-bff")).isEmpty();
        assertThat(s.redeemLoginTicket("", "gateway-bff")).isEmpty();
    }

    @Test
    void wrongSecondFactorCodesUseUpTheChallenge() {
        SignInChallenge challenge = SignInChallenge.mfa(ada.id(), "gateway-bff", NOW);
        store.insertChallenge(challenge);
        when(users.findById(ada.id())).thenReturn(Optional.of(ada));
        when(signIn.verifySecondFactor(ada.id(), MfaFactor.TOTP, "000000")).thenReturn(false);
        when(signIn.verifySecondFactor(ada.id(), MfaFactor.TOTP, "123456")).thenReturn(true);
        when(signIn.trustBrowser(ada.id(), "Mozilla/5.0")).thenReturn("trusted");
        FirstPartySignInServiceImpl s = service();

        assertThatThrownBy(() -> s.verifySecondFactor(new SecondFactorSubmission(challenge.id(), MfaFactor.TOTP,
                "000000", false, CTX))).isInstanceOfSatisfying(SignInFailed.class,
                        ex -> assertThat(ex.attemptsLeft()).isEqualTo(4));
        verify(protection).recordFailure(any());

        SignInStep ok = s.verifySecondFactor(new SecondFactorSubmission(challenge.id(), MfaFactor.TOTP, "123456",
                true, CTX));
        assertThat(ok.status()).isEqualTo(SignInStep.Status.AUTHENTICATED);
        assertThat(ok.trustedDeviceToken()).isEqualTo("trusted");
        assertThat(store.tickets.values()).extracting(LoginTicket::method).containsExactly(SignInMethod.PASSWORD_MFA);

        assertThatThrownBy(() -> s.verifySecondFactor(new SecondFactorSubmission(challenge.id(), MfaFactor.TOTP,
                "123456", false, CTX))).isInstanceOfSatisfying(SignInFailed.class,
                        ex -> assertThat(ex.code()).isEqualTo("identity.challenge-invalid"));
    }

    @Test
    void rejectedPasskeyIsCountedAndTheChallengeIsSpent() {
        when(passkeys.newRequest()).thenReturn(new PasskeyAssertions.PasskeyRequest("Y2hhbGxlbmdl", "localhost",
                "{}"));
        when(passkeys.verify(any(), eq("{\"bad\":true}"))).thenReturn(Optional.empty());
        FirstPartySignInServiceImpl s = service();
        PasskeyChallengeView options = s.passkeyOptions(CTX);

        assertThatThrownBy(() -> s.verifyPasskey(options.challengeId(), "{\"bad\":true}", CTX))
                .isInstanceOf(SignInFailed.class);
        verify(protection).recordFailure(any());
        assertThat(store.challenges.get(options.challengeId()).completedAt()).isNotNull();
    }

    /** In-memory store with the same single-use semantics as the JDBC adapter. */
    private static final class FakeStore implements FirstPartySignInStore {

        private final Map<UUID, SignInChallenge> challenges = new HashMap<>();
        private final Map<String, LoginTicket> tickets = new HashMap<>();

        @Override
        public void insertChallenge(SignInChallenge challenge) {
            challenges.put(challenge.id(), challenge);
        }

        @Override
        public Optional<SignInChallenge> lockChallenge(UUID id) {
            return Optional.ofNullable(challenges.get(id));
        }

        @Override
        public void updateChallenge(SignInChallenge challenge) {
            challenges.put(challenge.id(), challenge);
        }

        @Override
        public void insertTicket(LoginTicket ticket) {
            tickets.put(ticket.tokenHash(), ticket);
        }

        @Override
        public Optional<LoginTicket> burnTicket(String tokenHash, Instant now) {
            LoginTicket t = tickets.get(tokenHash);
            if (t == null || t.usedAt() != null) {
                return Optional.empty();
            }
            tickets.put(tokenHash, new LoginTicket(t.tokenHash(), t.userId(), t.clientId(), t.method(),
                    t.createdAt(), t.expiresAt(), now));
            return Optional.of(t);
        }

        @Override
        public int purgeExpired(Instant before) {
            return 0;
        }
    }
}
