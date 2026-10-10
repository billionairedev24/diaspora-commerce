package com.afrotransact.retail.domain;

import java.time.Instant;

/** When a shipment reached a stage and who recorded it. */
public record Milestone(ShipmentStage stage, Instant at, String by) {
}
