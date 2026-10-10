package com.afrotransact.identity.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Default {@link VerificationPolicy}. */
public class DefaultVerificationPolicy implements VerificationPolicy {

    private final SecureRandom random = new SecureRandom();

    @Override
    public void checkCanIssue(List<Instant> issuedInLastHour, Instant now) {
        List<Instant> recent = issuedInLastHour.stream()
                .filter(t -> t.isAfter(now.minus(Duration.ofHours(1))))
                .sorted(Comparator.reverseOrder())
                .toList();
        if (!recent.isEmpty()) {
            Duration since = Duration.between(recent.get(0), now);
            if (since.compareTo(MIN_RESEND_INTERVAL) < 0) {
                throw new RateLimitExceeded("identity.resend-too-soon",
                        "Wait a few seconds before asking for another code", MIN_RESEND_INTERVAL.minus(since));
            }
        }
        if (recent.size() >= MAX_PER_HOUR) {
            Instant oldest = recent.get(recent.size() - 1);
            throw new RateLimitExceeded("identity.too-many-codes",
                    "Too many codes requested. Try again later", Duration.between(now, oldest.plus(Duration.ofHours(1))));
        }
    }

    @Override
    public String newCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        return String.format("%0" + CODE_LENGTH + "d", random.nextInt(bound));
    }

    @Override
    public String hash(UUID codeId, String code) {
        return SecretHashing.sha256Hex(codeId + ":" + code.trim());
    }

    @Override
    public VerificationOutcome check(VerificationCode stored, String submitted, Instant now) {
        if (stored.consumed()) {
            return VerificationOutcome.ALREADY_USED;
        }
        if (stored.attempts() >= MAX_ATTEMPTS) {
            return VerificationOutcome.TOO_MANY_ATTEMPTS;
        }
        if (stored.expired(now)) {
            return VerificationOutcome.EXPIRED;
        }
        if (submitted == null || !SecretHashing.constantTimeEquals(stored.codeHash(), hash(stored.id(), submitted))) {
            return VerificationOutcome.WRONG_CODE;
        }
        return VerificationOutcome.VALID;
    }
}
