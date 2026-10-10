package com.afrotransact.fulfilment.domain;

import java.time.Instant;

/**
 * A problem reported at a drop-off.
 *
 * @param type       what went wrong
 * @param resolution what the driver does next
 * @param note       note for the customer and dispatch
 * @param photo      evidence photo (required for access, damage and unsafe)
 * @param at         device time of the report
 */
public record DeliveryProblem(DeliveryProblemType type, ProblemResolution resolution, String note,
                              PhotoCapture photo, Instant at) {
}
