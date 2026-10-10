package com.afrotransact.deliveryruns.application;

import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;

/**
 * Result of a reservation.
 *
 * @param slot           the order's slot
 * @param run            the run that holds it (the next run when the requested one was full or closed)
 * @param requestedRunId the run asked for
 * @param moved          true when the slot is on another run than the one asked for
 * @param created        false when the order already held this slot (idempotent repeat)
 */
public record SlotReservation(RunOrder slot, Run run, UUID requestedRunId, boolean moved, boolean created) {
}
