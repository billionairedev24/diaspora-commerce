package com.afrotransact.ai.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.ai.domain.SpendCapPreconditionException;

/** Persistence of the saved daily spend cap and its change history (database of ai-gateway). */
public interface SpendCapStore {

    /** The saved cap, empty while the deployment default is in force. */
    Optional<Saved> current();

    /**
     * Saves a new cap and appends the change to the history in one transaction.
     *
     * @param expectedVersion the version the editor loaded (0 = nothing saved yet)
     * @return the new saved state
     * @throws SpendCapPreconditionException (412) when the stored version is not
     *         {@code expectedVersion}
     */
    Saved save(long newCapMicros, long expectedVersion, String changedBy, String reason, Instant at);

    /** The changes, newest first. */
    List<Change> history(int limit);

    /** The saved cap: its version is the ETag. */
    record Saved(long capMicros, long version, String changedBy, Instant changedAt, String reason) {
    }

    /** One audit row. {@code previousMicros} is null when the value before was the deployment default. */
    record Change(UUID id, long version, Long previousMicros, boolean previousWasDefault, long newMicros,
                  String changedBy, Instant changedAt, String reason) {
    }
}
