package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the economics audit trail: the cost model before (the environment defaults for the first save) and
 * after, who changed it, when and why. Append-only.
 */
public record EconomicsChange(UUID id, long version, EconomicsSettings before, EconomicsSettings after, String actor,
                              String reason, Instant at) {
}
