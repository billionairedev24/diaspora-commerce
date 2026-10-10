package com.afrotransact.fulfilment.domain;

import java.util.List;
import java.util.function.ToLongFunction;

/** Ordering and idempotency of offline driver events (per device, sequence numbers starting at 1). */
public interface SequencePolicy {

    /**
     * @param lastApplied highest sequence consumed for the device (0 when none)
     * @param sequence    sequence number of the incoming event
     * @param seen        the event id was already received
     */
    SyncDecision decide(long lastApplied, long sequence, boolean seen);

    /** The batch in application order (ascending sequence, stable for equal numbers). */
    <T> List<T> inOrder(List<T> batch, ToLongFunction<T> sequence);
}
