package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Redeem attempts, kept to limit how fast anyone can guess codes. */
public interface GiftCardAttemptRepository {

    /**
     * Counts the failed attempts of {@code subject} since {@code since} and, when there are fewer than {@code max},
     * records a new attempt as failed and returns its id; empty when the limit is reached. Callers serialise on the
     * subject, so a burst of parallel guesses cannot slip past the limit.
     */
    OptionalLong reserve(String subject, Instant since, int max, Instant at);

    /** The attempt found a valid code: it no longer counts as a failure. */
    void succeed(long attemptId);

    /** When the oldest failed attempt since {@code since} happened (the window starts to free up then). */
    Optional<Instant> oldestFailureSince(String subject, Instant since);
}
