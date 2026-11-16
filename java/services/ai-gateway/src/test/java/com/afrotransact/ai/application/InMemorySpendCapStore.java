package com.afrotransact.ai.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.ai.domain.SpendCapPreconditionException;
import com.afrotransact.ai.port.SpendCapStore;

/** Test double with the real store's version rule (the adapter's own SQL is covered by SpendCapIT). */
class InMemorySpendCapStore implements SpendCapStore {

    private Saved saved;
    private final List<Change> changes = new ArrayList<>();
    int reads;
    boolean failing;

    @Override
    public Optional<Saved> current() {
        reads++;
        if (failing) {
            throw new IllegalStateException("database down");
        }
        return Optional.ofNullable(saved);
    }

    @Override
    public Saved save(long newCapMicros, long expectedVersion, String changedBy, String reason, Instant at) {
        long stored = saved == null ? 0 : saved.version();
        if (stored != expectedVersion) {
            throw SpendCapPreconditionException.mismatch();
        }
        changes.add(new Change(UUID.randomUUID(), stored + 1, saved == null ? null : saved.capMicros(), saved == null,
                newCapMicros, changedBy, at, reason));
        saved = new Saved(newCapMicros, stored + 1, changedBy, at, reason);
        return saved;
    }

    @Override
    public List<Change> history(int limit) {
        List<Change> newestFirst = new ArrayList<>(changes);
        Collections.reverse(newestFirst);
        return newestFirst.stream().limit(limit).toList();
    }
}
