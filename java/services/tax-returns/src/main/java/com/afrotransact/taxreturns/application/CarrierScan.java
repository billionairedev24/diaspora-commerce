package com.afrotransact.taxreturns.application;

import java.time.Instant;

/** The fields of carrier-gateway's {@code carrier.tracking.updated} event returns need. */
public record CarrierScan(String carrier, String trackingNumber, String reference, String state,
                          Instant occurredAt) {
}
