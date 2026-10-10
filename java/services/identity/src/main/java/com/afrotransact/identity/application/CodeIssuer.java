package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.domain.VerificationCode;
import com.afrotransact.identity.domain.VerificationOutcome;
import com.afrotransact.identity.domain.VerificationPolicy;
import com.afrotransact.identity.domain.VerificationPurpose;
import com.afrotransact.identity.port.VerificationCodeRepository;
import com.afrotransact.identity.port.VerificationCodeSender;
import com.afrotransact.identity.port.VerificationMessage;
import org.springframework.stereotype.Component;

/** Issues, rate-limits and checks hashed one-time codes for every purpose (email, SMS sign-in, SMS enrolment). */
@Component
class CodeIssuer {

    private final VerificationCodeRepository codes;
    private final VerificationCodeSender sender;
    private final VerificationPolicy policy;
    private final Clock clock;

    CodeIssuer(VerificationCodeRepository codes, VerificationCodeSender sender, VerificationPolicy policy,
               Clock clock) {
        this.codes = codes;
        this.sender = sender;
        this.policy = policy;
        this.clock = clock;
    }

    /** Sends a new code, enforcing 1 per 30 seconds and 5 per hour. Returns its expiry. */
    Instant issue(UserAccount user, VerificationPurpose purpose, String channel, String destination) {
        Instant now = Instant.now(clock);
        policy.checkCanIssue(codes.issuedSince(user.id(), purpose, now.minus(Duration.ofHours(1))), now);
        String code = policy.newCode();
        UUID id = UUID.randomUUID();
        Instant expires = now.plus(VerificationPolicy.TIME_TO_LIVE);
        codes.insert(new VerificationCode(id, user.id(), purpose, destination, policy.hash(id, code), now, expires,
                null, 0));
        sender.send(new VerificationMessage(user.id(), purpose, channel, destination, user.firstName(), code,
                expires));
        return expires;
    }

    /** Checks and consumes the latest code; throws {@link IdentityRuleViolation} with a field error otherwise. */
    VerificationCode consume(UUID userId, VerificationPurpose purpose, String submitted) {
        VerificationCode stored = codes.latest(userId, purpose).orElseThrow(() -> invalid(
                "identity.code-not-found", "Ask for a new code"));
        Instant now = Instant.now(clock);
        VerificationOutcome outcome = policy.check(stored, submitted, now);
        switch (outcome) {
            case VALID -> {
                VerificationCode used = stored.consume(now);
                codes.update(used);
                return used;
            }
            case WRONG_CODE -> {
                codes.update(stored.failedAttempt());
                throw invalid("identity.invalid-code", "That code isn't right");
            }
            case EXPIRED -> throw invalid("identity.code-expired", "That code has expired. Ask for a new one");
            case TOO_MANY_ATTEMPTS -> throw invalid("identity.too-many-attempts",
                    "Too many wrong codes. Ask for a new one");
            default -> throw invalid("identity.code-used", "That code was already used. Ask for a new one");
        }
    }

    private static IdentityRuleViolation invalid(String code, String message) {
        return new IdentityRuleViolation(code, message, Map.of("code", message));
    }
}
