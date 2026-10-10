package com.afrotransact.fulfilment.domain;

import java.time.Instant;

/** One logged contact step of the current delivery attempt (device time). */
public record ContactAttempt(ContactKind kind, Instant at) {
}
