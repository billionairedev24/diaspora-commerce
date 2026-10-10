package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;

/** Run slots held by orders. At most one active (RESERVED / CONFIRMED) slot per order id. */
public interface RunOrderRepository {

    /** @throws DuplicateEntryException when the order already holds an active slot */
    RunOrder insert(RunOrder order);

    RunOrder update(RunOrder order);

    Optional<RunOrder> findActiveByOrderId(String orderId);

    /** The order's most recent slot in this run, whatever its status. */
    Optional<RunOrder> findLatest(UUID runId, String orderId);

    /** Slots of a run, optionally by status, in reservation order. */
    List<RunOrder> findByRun(UUID runId, RunOrderStatus status);

    /** Active slots of a run, in reservation order. */
    List<RunOrder> findActiveByRun(UUID runId);
}
