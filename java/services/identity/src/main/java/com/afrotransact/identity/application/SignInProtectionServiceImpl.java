package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.SignInLocked;
import com.afrotransact.identity.domain.RateLimitExceeded;
import com.afrotransact.identity.domain.SignInAuditEntry;
import com.afrotransact.identity.domain.SignInThrottlePolicy;
import com.afrotransact.identity.domain.ThrottleScope;
import com.afrotransact.identity.domain.ThrottleState;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.SignInAuditLog;
import com.afrotransact.identity.port.SignInThrottleRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class SignInProtectionServiceImpl implements SignInProtectionService {

    private static final Logger LOG = LoggerFactory.getLogger(SignInProtectionServiceImpl.class);

    private final SignInThrottleRepository throttles;
    private final SignInThrottlePolicy policy;
    private final SignInAuditLog audit;
    private final UserAccountRepository users;
    private final DomainEventPublisher events;
    private final Clock clock;

    SignInProtectionServiceImpl(SignInThrottleRepository throttles, SignInThrottlePolicy policy, SignInAuditLog audit,
                                UserAccountRepository users, DomainEventPublisher events, Clock clock) {
        this.throttles = throttles;
        this.policy = policy;
        this.audit = audit;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Duration> lockedFor(String email, String ip) {
        Instant now = Instant.now(clock);
        Optional<Duration> account = key(email).flatMap(k -> policy.lockedFor(
                throttles.find(ThrottleScope.ACCOUNT, k, now), now));
        Optional<Duration> address = key(ip).flatMap(k -> policy.lockedFor(throttles.find(ThrottleScope.IP, k, now),
                now));
        return longest(account, address);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = RateLimitExceeded.class)
    public void requireNotLocked(SignInAttempt attempt) {
        Optional<Duration> locked = lockedFor(attempt.email(), attempt.ip());
        if (locked.isPresent()) {
            append(attempt, "BLOCKED", "locked");
            throw locked(locked.get());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Duration> recordFailure(SignInAttempt attempt) {
        Instant now = Instant.now(clock);
        append(attempt, "FAILED", attempt.reason());
        Optional<Duration> account = key(attempt.email()).flatMap(k -> count(ThrottleScope.ACCOUNT, k, attempt, now));
        Optional<Duration> address = key(attempt.ip()).flatMap(k -> count(ThrottleScope.IP, k, attempt, now));
        return longest(account, address);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(SignInAttempt attempt) {
        Instant now = Instant.now(clock);
        key(attempt.email()).ifPresent(k -> {
            ThrottleState s = throttles.lock(ThrottleScope.ACCOUNT, k, now);
            throttles.save(policy.onSuccess(s, now), now);
        });
        append(attempt, "SUCCEEDED", attempt.reason());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void audit(SignInAttempt attempt, String outcome) {
        append(attempt, outcome, attempt.reason());
    }

    private Optional<Duration> count(ThrottleScope scope, String key, SignInAttempt attempt, Instant now) {
        ThrottleState before = throttles.lock(scope, key, now);
        ThrottleState after = policy.onFailure(before, now);
        throttles.save(after, now);
        if (!before.lockedAt(now) && after.lockedAt(now)) {
            locked(scope, key, after, attempt, now);
        }
        return policy.lockedFor(after, now);
    }

    private void locked(ThrottleScope scope, String key, ThrottleState state, SignInAttempt attempt, Instant now) {
        Duration lock = Duration.between(now, state.lockedUntil());
        String userId = attempt.userId() != null ? attempt.userId().toString() : null;
        if (scope == ThrottleScope.ACCOUNT && userId == null) {
            userId = users.findByEmail(key).map(UserAccount::id).map(UUID::toString).orElse(null);
        }
        append(new SignInAttempt(userId == null ? null : UUID.fromString(userId), attempt.email(), attempt.ip(),
                attempt.userAgent(), attempt.clientId(), attempt.channel(), attempt.method(), null), "LOCKED",
                scope.name().toLowerCase(Locale.ROOT) + "-locked");
        LOG.warn("Sign-in locked: scope={} user={} ip={} for {}s", scope, userId, attempt.ip(), lock.toSeconds());
        SignInLocked payload = new SignInLocked(scope.name(), scope == ThrottleScope.ACCOUNT ? userId : null,
                scope == ThrottleScope.IP ? key : null, attempt.channel(), state.lockouts(), lock.toSeconds(),
                state.lockedUntil(), now);
        if (scope == ThrottleScope.IP) {
            events.publish(IdentityEvents.SIGN_IN_LOCKED, IdentityEvents.AGGREGATE, "ip:" + key, payload);
        } else if (userId != null) {
            // Unknown emails are locked too (no enumeration) but there is nobody to tell.
            events.publish(IdentityEvents.SIGN_IN_LOCKED, IdentityEvents.AGGREGATE, userId, payload);
        }
    }

    private void append(SignInAttempt a, String outcome, String reason) {
        audit.append(new SignInAuditEntry(Instant.now(clock), a.userId(), a.email(), a.clientId(), a.channel(),
                a.method(), outcome, reason, a.ip(), a.userAgent()));
    }

    static RateLimitExceeded locked(Duration remaining) {
        return new RateLimitExceeded("identity.sign-in-locked",
                "Too many sign-in attempts. Try again later or reset your password", remaining);
    }

    private static Optional<String> key(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static Optional<Duration> longest(Optional<Duration> a, Optional<Duration> b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        return Optional.of(a.get().compareTo(b.get()) >= 0 ? a.get() : b.get());
    }
}
