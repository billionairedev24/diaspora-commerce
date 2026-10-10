package com.afrotransact.fulfilment.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.FulfilmentTask;
import com.afrotransact.fulfilment.domain.TaskStatus;

/** Pick/pack tasks. */
public interface TaskRepository {

    /** Inserts unless a task for the shipment exists. @return true when inserted */
    boolean insert(FulfilmentTask task);

    /** Loads and locks the task for update. */
    Optional<FulfilmentTask> lock(String id);

    Optional<FulfilmentTask> find(String id);

    Optional<FulfilmentTask> findByShipment(String shipmentId);

    /** Saves with optimistic locking (version must match). */
    void update(FulfilmentTask task);

    List<FulfilmentTask> list(String nodeId, TaskStatus status, int limit);
}
