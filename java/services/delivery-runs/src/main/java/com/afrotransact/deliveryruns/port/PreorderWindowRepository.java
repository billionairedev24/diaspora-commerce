package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.PreorderWindow;

/** Pre-order windows with their targets and creatives. */
public interface PreorderWindowRepository {

    /** @throws DuplicateEntryException when the code is taken */
    PreorderWindow insert(PreorderWindow window);

    /** Optimistic: fails with {@link ConcurrentUpdateException} when {@code window.version()} is stale. */
    PreorderWindow update(PreorderWindow window);

    Optional<PreorderWindow> findById(UUID id);

    /** The window whose orders the run carries (the run was handed over from it). */
    Optional<PreorderWindow> findByRunId(UUID runId);

    /** {@code SELECT … FOR UPDATE}. */
    Optional<PreorderWindow> lockById(UUID id);

    /** {@code SELECT … FOR UPDATE SKIP LOCKED}: empty when another replica holds it (scheduler). */
    Optional<PreorderWindow> tryLock(UUID id);

    /** Every window, newest opening first. */
    List<PreorderWindow> findAll();

    /** Published (not draft) windows, newest opening first. */
    List<PreorderWindow> findPublished();
}
