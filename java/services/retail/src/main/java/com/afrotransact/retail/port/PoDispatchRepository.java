package com.afrotransact.retail.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.PoDispatch;

/** Deliveries of sent POs to suppliers. */
public interface PoDispatchRepository {

    /** Inserts unless the PO already has a dispatch on that channel. */
    void insertIfAbsent(PoDispatch dispatch);

    List<PoDispatch> byPo(UUID poId);

    /** Pending dispatches due at {@code now}, oldest first. */
    List<UUID> due(Instant now, int limit);

    /** Row-locks a pending dispatch for this transaction; empty when another replica holds it or it is done. */
    Optional<PoDispatch> claim(UUID id);

    PoDispatch update(PoDispatch dispatch);
}
