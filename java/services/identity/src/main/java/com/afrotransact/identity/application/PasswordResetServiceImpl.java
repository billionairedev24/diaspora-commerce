package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.PasswordChanged;
import com.afrotransact.identity.application.IdentityEventPayloads.PasswordResetFederated;
import com.afrotransact.identity.application.IdentityEventPayloads.Recipient;
import com.afrotransact.identity.domain.AccessPolicy;
import com.afrotransact.identity.domain.FederatedIdentity;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.PasswordPolicy;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.identity.domain.SignInAuditEntry;
import com.afrotransact.identity.domain.SignInThrottlePolicy;
import com.afrotransact.identity.domain.ThrottleScope;
import com.afrotransact.identity.domain.ThrottleState;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationPolicy;
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
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link PasswordResetService}. The limits reuse the sign-in protection's counters and policy (same
 * escalating lockouts) under their own keys, so a reset never locks the person's sign-in and the other way round:
 * {@code reset-request:<email|ip>} counts code requests, {@code reset:<email|ip>} counts wrong codes. Keys are
 * counted for every email, with or without an account, so lockouts reveal nothing either.
 */
@Service
class PasswordResetServiceImpl implements PasswordResetService {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetServiceImpl.class);

    static final String METHOD = "PASSWORD_RESET";
    static final String REQUEST_KEY = "reset-request:";
    static final String CONFIRM_KEY = "reset:";
    private static final int MAX_KEY = 254;
    private static final Map<String, String> PROVIDER_NAMES = Map.of("google", "Google", "apple", "Apple",
            "facebook", "Facebook");

    private final UserAccountRepository users;
    private final FederatedIdentityRepository federated;
    private final PhoneVerificationRepository phones;
    private final CodeIssuer codes;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher hasher;
    private final SessionStore sessions;
    private final MfaRepository mfa;
    private final SignInThrottleRepository throttles;
    private final SignInThrottlePolicy throttlePolicy;
    private final SignInAuditLog audit;
    private final AccessChangeLog changeLog;
    private final AccessProfileLoader profiles;
    private final AccessPolicy accessPolicy;
    private final DomainEventPublisher events;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    PasswordResetServiceImpl(UserAccountRepository users, FederatedIdentityRepository federated,
                             PhoneVerificationRepository phones, CodeIssuer codes, PasswordPolicy passwordPolicy,
                             PasswordHasher hasher, SessionStore sessions, MfaRepository mfa,
                             SignInThrottleRepository throttles, SignInThrottlePolicy throttlePolicy,
                             SignInAuditLog audit, AccessChangeLog changeLog, AccessProfileLoader profiles,
                             AccessPolicy accessPolicy, DomainEventPublisher events, Clock clock) {
        this.users = users;
        this.federated = federated;
        this.phones = phones;
        this.codes = codes;
        this.passwordPolicy = passwordPolicy;
        this.hasher = hasher;
        this.sessions = sessions;
        this.mfa = mfa;
        this.throttles = throttles;
        this.throttlePolicy = throttlePolicy;
        this.audit = audit;
        this.changeLog = changeLog;
        this.profiles = profiles;
        this.accessPolicy = accessPolicy;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Duration codeTimeToLive() {
        return VerificationPolicy.TIME_TO_LIVE;
    }

    @Override
    @Transactional(noRollbackFor = RateLimitExceeded.class)
    public void request(PasswordResetRequest r) {
        Instant now = Instant.now(clock);
        String email = UserAccount.normaliseEmail(r.email());
        Optional<UserAccount> account = users.findByEmail(email).filter(UserAccount::enabled);
        UUID userId = account.map(UserAccount::id).orElse(null);
        Optional<Duration> locked = longest(lockedFor(REQUEST_KEY, email, r.ip(), now));
        if (locked.isPresent()) {
            append(now, userId, email, r.ip(), r.userAgent(), r.clientId(), r.channel(), "BLOCKED", "request-locked");
            throw locked(locked.get());
        }
        count(REQUEST_KEY, email, r.ip(), now);
        String outcome = account.map(u -> deliver(u, now)).orElse("no-account");
        append(now, userId, email, r.ip(), r.userAgent(), r.clientId(), r.channel(), "REQUESTED", outcome);
    }

    /** Sends the code (or the "use your provider" email); returns the audit reason. */
    private String deliver(UserAccount u, Instant now) {
        if (!u.hasPassword()) {
            List<String> providers = federated.forUser(u.id()).stream().map(FederatedIdentity::provider)
                    .distinct().sorted().map(p -> PROVIDER_NAMES.getOrDefault(p, p)).toList();
            if (providers.isEmpty()) {
                // Passkey-only accounts: passkeys are unchanged by a reset and nothing else can be offered by email.
                return "no-password";
            }
            events.publish(IdentityEvents.PASSWORD_RESET_FEDERATED, IdentityEvents.AGGREGATE, u.id().toString(),
                    new PasswordResetFederated(new Recipient(u.id().toString(), u.email(), null), u.id().toString(),
                            u.firstName(), providers, true));
            return "federated-only";
        }
        Map<String, String> destinations = new LinkedHashMap<>();
        destinations.put("EMAIL", u.email());
        // Text the same code only to a phone the person confirmed, and only once the email itself is confirmed.
        if (u.emailVerified() && u.phone() != null
                && phones.verifiedPhone(u.id()).map(u.phone()::equals).orElse(false)) {
            destinations.put("SMS", u.phone());
        }
        try {
            codes.issueToAll(u, VerificationPurpose.PASSWORD_RESET, destinations);
        } catch (RateLimitExceeded ex) {
            // A code went out moments ago (or five this hour): the answer stays the same, nothing new is sent.
            LOG.info("Password reset code not re-sent for {}: {}", u.id(), ex.code());
            return "code-throttled";
        }
        return destinations.containsKey("SMS") ? "code-sent-email-sms" : "code-sent-email";
    }

    @Override
    @Transactional(noRollbackFor = {RateLimitExceeded.class, IdentityRuleViolation.class})
    public PasswordResetCompleted complete(PasswordResetCompletion c) {
        Instant now = Instant.now(clock);
        String email = UserAccount.normaliseEmail(c.email());
        Optional<UserAccount> account = users.findByEmail(email).filter(UserAccount::enabled);
        UUID userId = account.map(UserAccount::id).orElse(null);
        Optional<Duration> locked = longest(lockedFor(CONFIRM_KEY, email, c.ip(), now));
        if (locked.isPresent()) {
            append(now, userId, email, c.ip(), c.userAgent(), c.clientId(), c.channel(), "BLOCKED", "locked");
            throw locked(locked.get());
        }
        // The new password is checked first and the same way for every email (no enumeration); rules about the
        // person's own name and email are checked once the code proved the account, before the code is used up.
        Map<String, String> errors = new LinkedHashMap<>();
        String localPart = email == null || email.indexOf('@') < 0 ? "" : email.substring(0, email.indexOf('@'));
        List<String> violations = passwordPolicy.violations(c.newPassword(), List.of(localPart));
        if (!violations.isEmpty()) {
            errors.put("newPassword", String.join("; ", violations));
        }
        if (c.newPassword() == null || !c.newPassword().equals(c.confirmPassword())) {
            errors.put("confirmPassword", "Passwords don’t match");
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation(INVALID_PASSWORD, "Check the highlighted fields", errors);
        }
        String code = c.code() == null ? "" : c.code().trim();
        if (account.isEmpty() || !account.get().hasPassword() || !code.matches("\\d{6}")) {
            throw wrongCode(c, email, userId, now, account.isEmpty() ? "no-account" : "wrong-code");
        }
        UserAccount u = account.get();
        try {
            codes.consume(u.id(), VerificationPurpose.PASSWORD_RESET, code, () -> {
                List<String> personal = passwordPolicy.violations(c.newPassword(),
                        List.of(u.firstName(), u.lastName(), u.emailLocalPart()));
                if (!personal.isEmpty()) {
                    throw new IdentityRuleViolation(INVALID_PASSWORD, "Check the highlighted fields",
                            Map.of("newPassword", String.join("; ", personal)));
                }
            });
        } catch (IdentityRuleViolation ex) {
            if (INVALID_PASSWORD.equals(ex.code())) {
                throw ex;
            }
            throw wrongCode(c, email, u.id(), now, ex.code().replace("identity.", ""));
        }
        UserAccount updated = u.withPassword(hasher.hash(c.newPassword()), now);
        // The code reached the inbox, so a still-unconfirmed email is confirmed now.
        users.update(u.emailVerified() ? updated : updated.verified(now));
        int revoked = sessions.revokeAll(u.id(), Optional.empty()) + sessions.endBrowserSessions(u.id());
        mfa.deleteTrustedBrowsers(u.id());
        clear(CONFIRM_KEY, email, now);
        changeLog.append(u.id().toString(), "password.reset", u.id(), null, "password", null);
        append(now, u.id(), email, c.ip(), c.userAgent(), c.clientId(), c.channel(), "SUCCEEDED",
                "sessions-revoked:" + revoked);
        events.publish(IdentityEvents.PASSWORD_CHANGED, IdentityEvents.AGGREGATE, u.id().toString(),
                new PasswordChanged(new Recipient(u.id().toString(), u.email(), null), u.id().toString(),
                        u.firstName(), "RESET", now, revoked, true));
        boolean staff = accessPolicy.resolve(profiles.load(u)).staff();
        return new PasswordResetCompleted(u.email(), revoked, staff);
    }

    /** Counts the failure (lockout after the limit) and answers like every other bad code. */
    private IdentityRuleViolation wrongCode(PasswordResetCompletion c, String email, UUID userId, Instant now,
                                            String reason) {
        append(now, userId, email, c.ip(), c.userAgent(), c.clientId(), c.channel(), "FAILED", reason);
        Optional<Duration> lock = longest(count(CONFIRM_KEY, email, c.ip(), now));
        if (lock.isPresent()) {
            append(now, userId, email, c.ip(), c.userAgent(), c.clientId(), c.channel(), "LOCKED", "too-many-codes");
            throw locked(lock.get());
        }
        String message = "That code isn’t right or has expired. Check it, or ask for a new one";
        return new IdentityRuleViolation(INVALID_CODE, message, Map.of("code", message));
    }

    private List<Optional<Duration>> lockedFor(String prefix, String email, String ip, Instant now) {
        List<Optional<Duration>> out = new ArrayList<>();
        key(prefix, email).ifPresent(k -> out.add(throttlePolicy.lockedFor(
                throttles.find(ThrottleScope.ACCOUNT, k, now), now)));
        key(prefix, ip).ifPresent(k -> out.add(throttlePolicy.lockedFor(throttles.find(ThrottleScope.IP, k, now),
                now)));
        return out;
    }

    private List<Optional<Duration>> count(String prefix, String email, String ip, Instant now) {
        List<Optional<Duration>> out = new ArrayList<>();
        key(prefix, email).ifPresent(k -> out.add(count(ThrottleScope.ACCOUNT, k, now)));
        key(prefix, ip).ifPresent(k -> out.add(count(ThrottleScope.IP, k, now)));
        return out;
    }

    private Optional<Duration> count(ThrottleScope scope, String key, Instant now) {
        ThrottleState after = throttlePolicy.onFailure(throttles.lock(scope, key, now), now);
        throttles.save(after, now);
        return throttlePolicy.lockedFor(after, now);
    }

    private void clear(String prefix, String email, Instant now) {
        key(prefix, email).ifPresent(k -> throttles.save(throttlePolicy.onSuccess(
                throttles.lock(ThrottleScope.ACCOUNT, k, now), now), now));
    }

    private void append(Instant at, UUID userId, String email, String ip, String userAgent, String clientId,
                        String channel, String outcome, String reason) {
        audit.append(new SignInAuditEntry(at, userId, cut(email, MAX_KEY), cut(clientId, 100), channel, METHOD,
                outcome, cut(reason, 80), cut(ip, 64), cut(userAgent, 300)));
    }

    private static Optional<String> key(String prefix, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(cut(prefix + value.trim().toLowerCase(Locale.ROOT), MAX_KEY));
    }

    private static Optional<Duration> longest(List<Optional<Duration>> durations) {
        return durations.stream().flatMap(Optional::stream).max(Duration::compareTo);
    }

    private static RateLimitExceeded locked(Duration remaining) {
        return new RateLimitExceeded(LOCKED, "Too many attempts. Try again later", remaining);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
