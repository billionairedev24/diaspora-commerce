package com.afrotransact.seller.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.afrotransact.seller.domain.RewardComparison;

/** S299: the once-per-window decisions of rules checked at window end (unique per rule, organisation and window). */
public interface RewardWindowCheckRepository {

    /** One decision. {@code outcome}: QUALIFIED (with the grant) or NOT_QUALIFIED. */
    record Check(UUID ruleId, String orgId, LocalDate windowStart, Instant windowEnd, Instant checkedAt, long value,
                 long threshold, RewardComparison comparison, String outcome, String reason, UUID grantId) {
    }

    /** Records the decision unless this window was already decided; false when it was (nothing is written). */
    boolean insertOnce(Check c);

    boolean exists(UUID ruleId, String orgId, LocalDate windowStart);
}
