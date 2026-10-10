package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One capture of a payment, normally one per shipment on carrier acceptance.
 *
 * @param stripeFee    Stripe's fee for this capture (from its balance transaction, or the schedule's estimate)
 * @param feeEstimated true when Stripe had not reported the fee yet; reconciliation reports the difference
 */
public record Capture(UUID id, UUID paymentId, String shipmentId, Money amount, Money stripeFee,
                      boolean feeEstimated, String stripeChargeId, boolean finalCapture, List<String> lineIds,
                      UUID journalId, Instant capturedAt) {

    public Capture {
        lineIds = List.copyOf(lineIds);
    }
}
