package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.Optional;

/** Whether an organisation has met its plan's "charge once the condition is met" condition (charging then stays on). */
public interface PlanPolicyStateRepository {

    /** When the organisation met its plan's charge-start condition (empty = not yet). */
    Optional<Instant> metAt(String orgId);

    void markMet(String orgId, Instant at);
}
