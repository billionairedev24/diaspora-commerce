package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RunOrder;

/** Service API for orders (S261) and fulfilment (S264). */
public interface RunSlotService {

    /** Takes a slot; a full or closed run rolls the order over to the location's next open run when allowed. */
    SlotReservation reserve(ReserveCommand command);

    /** Gives the order's slot back (idempotent); not once the run is out for delivery. */
    RunOrder release(UUID runId, String orderId);

    /** Confirmed orders of a confirmed (or later) run, with handover codes, for dispatch planning. */
    DispatchList dispatch(UUID runId);

    /** Ids of runs confirmed for dispatch (CONFIRMED or COLLECTING), earliest window first. */
    List<UUID> dispatchable();
}
