package com.afrotransact.deliveryruns.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunStatus;

/** Runs. The {@code lock…} methods take row locks for the caller's transaction. */
public interface RunRepository {

    Run insert(Run run);

    /** Optimistic: fails with {@link ConcurrentUpdateException} when {@code run.version()} is stale. */
    Run update(Run run);

    Optional<Run> findById(UUID id);

    /** {@code SELECT … FOR UPDATE}: serialises slot reservations and transitions of one run. */
    Optional<Run> lockById(UUID id);

    /** Runs, newest window first, optionally by location and statuses. */
    List<Run> find(UUID locationId, Set<RunStatus> statuses, int limit);

    /** Runs taking orders at {@code now} for these locations, earliest cutoff first. */
    List<Run> findAccepting(Collection<UUID> locationIds, Instant now);

    /**
     * Locks ({@code FOR UPDATE}, earliest cutoff first so concurrent reservations lock in the same order) the next
     * OPEN / EXTENDED runs of a location taking orders whose cutoff is after {@code afterCutoff}.
     */
    List<Run> lockAcceptingAfter(UUID locationId, Instant afterCutoff, Instant now, int limit);

    /** Non-draft runs of a location whose window has not ended (location page), earliest first. */
    List<Run> findUpcoming(UUID locationId, Instant now);

    /** Ids of OPEN / EXTENDED runs whose cutoff has passed, earliest first (no lock). */
    List<UUID> dueForCutoff(Instant now, int limit);

    /** Ids of runs within {@code lead} of their cutoff whose reminder was not sent for that cutoff (no lock). */
    List<UUID> dueForReminder(Instant now, Duration lead, int limit);

    /** Ids of ANNOUNCED runs whose {@code opensAt} has come (no lock). */
    List<UUID> dueToOpen(Instant now, int limit);

    /**
     * {@code SELECT … FOR UPDATE SKIP LOCKED}: the run, or empty when another replica holds it. Jobs re-check the
     * run's state after locking, so each due run is processed exactly once across replicas.
     */
    Optional<Run> tryLock(UUID id);

    /** Deletes a DRAFT run. */
    void delete(UUID id);
}
