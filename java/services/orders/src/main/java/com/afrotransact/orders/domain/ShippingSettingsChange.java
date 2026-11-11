package com.afrotransact.orders.domain;

import java.time.Instant;
import java.util.UUID;

/** One version of the shipping settings: the record before (null for the first) and after, who changed it and why. */
public record ShippingSettingsChange(UUID id, String currency, long version, ShippingSettings before,
                                     ShippingSettings after, String actorId, String reason, Instant changedAt) {
}
