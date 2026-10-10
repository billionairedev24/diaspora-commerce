package com.afrotransact.fulfilment.domain;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Events of one device are consumed strictly in sequence: the next expected number is applied; a known event id is a
 * duplicate (idempotent replay); a reused number with a new id is a conflict; a number beyond the next expected one
 * waits for the gap to be filled.
 */
public final class DefaultSequencePolicy implements SequencePolicy {

    @Override
    public SyncDecision decide(long lastApplied, long sequence, boolean seen) {
        if (seen) {
            return SyncDecision.DUPLICATE;
        }
        if (sequence < 1 || sequence <= lastApplied) {
            return SyncDecision.SEQUENCE_CONFLICT;
        }
        return sequence == lastApplied + 1 ? SyncDecision.APPLY : SyncDecision.GAP;
    }

    @Override
    public <T> List<T> inOrder(List<T> batch, ToLongFunction<T> sequence) {
        return batch.stream().sorted(Comparator.comparingLong(sequence)).toList();
    }
}
