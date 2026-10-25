package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.deliveryruns.domain.EconomicsChange;
import com.afrotransact.deliveryruns.domain.EconomicsRevision;

/** The staff-saved driver cost model and its audit trail (S265). Empty until staff save once. */
public interface EconomicsSettingsRepository {

    /** The latest saved version. */
    Optional<EconomicsRevision> find();

    /** The saved version locked for update, so concurrent staff edits queue. */
    Optional<EconomicsRevision> lock();

    /** Stores the first version. @throws ConcurrentUpdateException when another save got there first */
    void insert(EconomicsRevision first);

    /** Stores {@code next} (version + 1) when the stored version is {@code next.version() - 1}, else throws. */
    void update(EconomicsRevision next);

    /** Appends to the audit trail. */
    void log(EconomicsChange change);

    /** The audit trail, newest first. */
    List<EconomicsChange> changes(int limit);
}
