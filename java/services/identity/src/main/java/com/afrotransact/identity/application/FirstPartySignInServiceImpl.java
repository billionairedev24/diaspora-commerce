package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.ChallengeKind;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.LoginTicket;
import com.afrotransact.identity.domain.MfaPolicy;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.identity.domain.SecretHashing;
import com.afrotransact.identity.domain.SignInChallenge;
import com.afrotransact.identity.domain.SignInFailed;
import com.afrotransact.identity.domain.SignInMethod;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.FirstPartySignInStore;
import com.afrotransact.identity.port.PasskeyAssertions;
import com.afrotransact.identity.port.PasskeyAssertions.PasskeyRequest;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class FirstPartySignInServiceImpl implements FirstPartySignInService {

    /** Expired challenges and tickets are kept this long (support investigations), then purged. */
    private static final Duration RETENTION = Duration.ofDays(1);
    private static final int TICKET_BYTES = 32;

    private final CurrentUser current;
    private final FirstPartySignInSettings settings;
    private final UserAccountRepository users;
    private final PasswordHasher hasher;
    private final SignInService signIn;
    private final SignInProtectionService protection;
    private final FirstPartySignInStore store;
    private final PasskeyAssertions passkeys;
    private final Clock clock;
    private volatile String dummyHash;

    FirstPartySignInServiceImpl(CurrentUser current, FirstPartySignInSettings settings, UserAccountRepository users,
                                PasswordHasher hasher, SignInService signIn, SignInProtectionService protection,
                                FirstPartySignInStore store, PasskeyAssertions passkeys, Clock clock) {
        this.current = current;
        this.settings = settings;
        this.users = users;
        this.hasher = hasher;
        this.signIn = signIn;
        this.protection = protection;
        this.store = store;
        this.passkeys = passkeys;
        this.clock = clock;
    }

    @Override
    @Transactional(noRollbackFor = {SignInFailed.class, ForbiddenActionException.class, RateLimitExceeded.class})
    public SignInStep password(PasswordSignIn command) {
        String client = requireFirstPartyClient();
        ClientContext ctx = context(command.context());
        String email = UserAccount.normaliseEmail(command.email());
        protection.requireNotLocked(attempt(null, email, ctx, client, "PASSWORD", null));
        UserAccount user = users.findByEmail(email).orElse(null);
        boolean matches = user != null && user.hasPassword()
                ? hasher.matches(command.password(), user.passwordHash())
                : dummyCheck(command.password());
        if (!matches) {
            protection.recordFailure(attempt(user == null ? null : user.id(), email, ctx, client, "PASSWORD",
                    user == null ? "unknown-account" : "bad-credentials"));
            throw new SignInFailed("identity.invalid-credentials", "Email or password is incorrect");
        }
        requireUsable(user, email, ctx, client, "PASSWORD");
        Optional<String> trusted = Optional.ofNullable(command.trustedDeviceToken()).filter(t -> !t.isBlank());
        SecondFactorChallenge factors = signIn.challenge(user.id(), trusted, false);
        if (factors.required()) {
            // The account counter is cleared only when every factor succeeded, so a known password does not buy
            // unlimited second-factor guesses.
            SignInChallenge challenge = SignInChallenge.mfa(user.id(), client, Instant.now(clock));
            store.insertChallenge(challenge);
            protection.audit(attempt(user.id(), email, ctx, client, "PASSWORD", null), "MFA_REQUIRED");
            return SignInStep.mfaRequired(challenge.id(), challenge.expiresAt(), factors);
        }
        return complete(user, client, SignInMethod.PASSWORD, "PASSWORD", ctx, null);
    }

    @Override
    @Transactional(noRollbackFor = {SignInFailed.class, IdentityRuleViolation.class, RateLimitExceeded.class})
    public SmsCodeSent sendSmsCode(UUID challengeId, ClientContext context) {
        String client = requireFirstPartyClient();
        SignInChallenge challenge = openChallenge(challengeId, ChallengeKind.MFA, client);
        Instant expires = signIn.sendSmsCode(challenge.userId());
        return new SmsCodeSent(signIn.challenge(challenge.userId(), Optional.empty(), false).smsHint(), expires);
    }

    @Override
    @Transactional(noRollbackFor = {SignInFailed.class, ForbiddenActionException.class, RateLimitExceeded.class})
    public SignInStep verifySecondFactor(SecondFactorSubmission s) {
        String client = requireFirstPartyClient();
        ClientContext ctx = context(s.context());
        SignInChallenge challenge = openChallenge(s.challengeId(), ChallengeKind.MFA, client);
        UserAccount user = users.findById(challenge.userId())
                .orElseThrow(FirstPartySignInServiceImpl::invalidChallenge);
        String method = "MFA_" + s.factor().name();
        protection.requireNotLocked(attempt(user.id(), user.email(), ctx, client, method, null));
        if (!signIn.verifySecondFactor(user.id(), s.factor(), s.code())) {
            SignInChallenge failed = challenge.failedAttempt();
            store.updateChallenge(failed);
            protection.recordFailure(attempt(user.id(), user.email(), ctx, client, method, "wrong-code"));
            throw new SignInFailed("identity.invalid-code", failed.attemptsLeft() > 0 ? "That code isn't right"
                    : "Too many wrong codes. Sign in again", failed.attemptsLeft());
        }
        store.updateChallenge(challenge.completed(Instant.now(clock)));
        requireUsable(user, user.email(), ctx, client, method);
        String trustedToken = s.trustDevice() ? signIn.trustBrowser(user.id(), ctx.userAgent()) : null;
        return complete(user, client, SignInMethod.PASSWORD_MFA, method, ctx, trustedToken);
    }

    @Override
    @Transactional(noRollbackFor = RateLimitExceeded.class)
    public PasskeyChallengeView passkeyOptions(ClientContext context) {
        String client = requireFirstPartyClient();
        ClientContext ctx = context(context);
        protection.requireNotLocked(attempt(null, null, ctx, client, "PASSKEY", null));
        PasskeyRequest request = passkeys.newRequest();
        SignInChallenge challenge = SignInChallenge.passkey(client, request.challenge(), request.rpId(),
                Instant.now(clock));
        store.insertChallenge(challenge);
        return new PasskeyChallengeView(challenge.id(), challenge.expiresAt(), request.optionsJson());
    }

    @Override
    @Transactional(noRollbackFor = {SignInFailed.class, ForbiddenActionException.class, RateLimitExceeded.class})
    public SignInStep verifyPasskey(UUID challengeId, String credentialJson, ClientContext context) {
        String client = requireFirstPartyClient();
        ClientContext ctx = context(context);
        protection.requireNotLocked(attempt(null, null, ctx, client, "PASSKEY", null));
        SignInChallenge challenge = openChallenge(challengeId, ChallengeKind.PASSKEY, client);
        // One assertion per challenge, whatever the outcome.
        store.updateChallenge(challenge.completed(Instant.now(clock)));
        Optional<UserAccount> owner = passkeys.verify(new PasskeyRequest(challenge.passkeyChallenge(),
                challenge.rpId(), null), credentialJson).flatMap(users::findById);
        if (owner.isEmpty()) {
            protection.recordFailure(attempt(null, null, ctx, client, "PASSKEY", "assertion-rejected"));
            throw new SignInFailed("identity.passkey-rejected", "That passkey couldn't be verified");
        }
        UserAccount user = owner.get();
        requireUsable(user, user.email(), ctx, client, "PASSKEY");
        return complete(user, client, SignInMethod.PASSKEY, "PASSKEY", ctx, null);
    }

    @Override
    @Transactional
    public Optional<RedeemedLoginTicket> redeemLoginTicket(String ticket, String clientId) {
        if (ticket == null || ticket.isBlank() || clientId == null) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        Optional<LoginTicket> burned = store.burnTicket(SecretHashing.sha256Hex(ticket.trim()), now);
        UUID userId = burned.map(LoginTicket::userId).orElse(null);
        if (burned.isEmpty() || !burned.get().redeemableBy(clientId, now)) {
            String reason = burned.isEmpty() ? "unknown-or-used" : burned.get().clientId().equals(clientId)
                    ? "expired" : "wrong-client";
            protection.audit(new SignInAttempt(userId, null, null, null, clientId, SignInAttempt.FIRST_PARTY,
                    "LOGIN_TICKET", reason), "TICKET_REJECTED");
            return Optional.empty();
        }
        protection.audit(new SignInAttempt(userId, null, null, null, clientId, SignInAttempt.FIRST_PARTY,
                "LOGIN_TICKET", burned.get().method().name().toLowerCase(Locale.ROOT)), "TICKET_REDEEMED");
        return Optional.of(new RedeemedLoginTicket(userId, burned.get().method(), burned.get().createdAt()));
    }

    private SignInStep complete(UserAccount user, String client, SignInMethod method, String auditMethod,
                                ClientContext ctx, String trustedToken) {
        Instant now = Instant.now(clock);
        String ticket = SecretHashing.randomToken(TICKET_BYTES);
        LoginTicket stored = new LoginTicket(SecretHashing.sha256Hex(ticket), user.id(), ticketClient(client, ctx),
                method, now,
                now.plus(LoginTicket.TTL), null);
        store.insertTicket(stored);
        store.purgeExpired(now.minus(RETENTION));
        protection.recordSuccess(attempt(user.id(), user.email(), ctx, client, auditMethod, "ticket-issued"));
        return SignInStep.authenticated(user.id(), ticket, stored.expiresAt(), trustedToken,
                trustedToken == null ? null : now.plus(MfaPolicy.TRUSTED_BROWSER_TTL));
    }

    /** The OAuth client that may redeem the ticket: the native app the BFF signs in for, else the BFF itself. */
    private String ticketClient(String caller, ClientContext ctx) {
        String app = ctx.appClientId();
        if (app == null || app.isBlank()) {
            return caller;
        }
        if (!settings.nativeClientIds().contains(app)) {
            throw new ForbiddenActionException("identity.native-client-not-allowed",
                    "That app cannot use in-app sign-in");
        }
        return app;
    }

    /** Correct credentials, but the account cannot sign in yet (403; safe to reveal once the password matched). */
    private void requireUsable(UserAccount user, String email, ClientContext ctx, String client, String method) {
        if (!user.emailVerified()) {
            protection.audit(attempt(user.id(), email, ctx, client, method, "email-not-verified"), "FAILED");
            throw new ForbiddenActionException("identity.email-not-verified",
                    "Confirm your email address before signing in");
        }
        if (!user.enabled()) {
            protection.audit(attempt(user.id(), email, ctx, client, method, "account-disabled"), "FAILED");
            throw new ForbiddenActionException("identity.account-disabled", "This account is disabled");
        }
    }

    private SignInChallenge openChallenge(UUID id, ChallengeKind kind, String client) {
        Instant now = Instant.now(clock);
        return store.lockChallenge(id).filter(c -> c.kind() == kind && c.usableBy(client, now))
                .orElseThrow(FirstPartySignInServiceImpl::invalidChallenge);
    }

    private static SignInFailed invalidChallenge() {
        return new SignInFailed("identity.challenge-invalid",
                "This sign-in attempt expired or was already used. Start again");
    }

    /** The caller must be a first-party client's service token with {@code auth:first-party}. */
    private String requireFirstPartyClient() {
        Actor actor = current.actor();
        if (actor.type() != PrincipalType.SERVICE || !actor.can(IdentityPermissions.AUTH_FIRST_PARTY)
                || !settings.clientIds().contains(actor.subject())) {
            throw new ForbiddenActionException("identity.first-party-client-required",
                    "Only the first-party BFF client may call the sign-in API");
        }
        return actor.subject();
    }

    /** Spends about as long as a real password check, so unknown emails are not revealed by timing. */
    private boolean dummyCheck(String password) {
        String hash = dummyHash;
        if (hash == null) {
            hash = hasher.hash(SecretHashing.randomToken(16));
            dummyHash = hash;
        }
        hasher.matches(password == null ? "" : password, hash);
        return false;
    }

    private static ClientContext context(ClientContext ctx) {
        return ctx == null ? new ClientContext(null, null, null) : ctx;
    }

    private static SignInAttempt attempt(UUID userId, String email, ClientContext ctx, String client, String method,
                                         String reason) {
        return new SignInAttempt(userId, email, ctx.ip(), ctx.userAgent(), client, SignInAttempt.FIRST_PARTY, method,
                reason);
    }
}
