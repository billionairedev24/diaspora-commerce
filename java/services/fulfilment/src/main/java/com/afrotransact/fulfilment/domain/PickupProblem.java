package com.afrotransact.fulfilment.domain;

import java.time.Instant;

/** A problem reported at a seller pickup. */
public record PickupProblem(PickupProblemType type, PickupResolution resolution, String note, PhotoCapture photo,
                            Instant at) {
}
