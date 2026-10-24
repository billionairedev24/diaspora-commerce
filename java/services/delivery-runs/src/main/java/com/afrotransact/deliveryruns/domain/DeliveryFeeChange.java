package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of the delivery fee audit trail: the rule before (null for its first version) and after, who changed it,
 * when and why. Append-only.
 */
public record DeliveryFeeChange(UUID id, FeeMethod method, String currency, long version, DeliveryFeeRule before,
                                DeliveryFeeRule after, String actor, String reason, Instant at) {
}
