package com.afrotransact.fulfilment.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Per-device sync cursor and the log of every driver event received (idempotency by event id). */
public interface DriverEventLog {

    /** Locks (creating if needed) the device cursor; returns the last consumed sequence. */
    long lockDevice(String deviceId, String driverId, Instant now);

    void advance(String deviceId, long sequence, Instant now);

    /** Last consumed sequence of a device (0 when unknown), without locking. */
    long lastSequence(String deviceId);

    Optional<Entry> find(String eventId);

    void record(Entry entry);

    List<Entry> recent(String routeId, String driverId, int limit);

    /**
     * A stored driver event and its outcome.
     *
     * @param payloadJson the event as received, with the handover code redacted
     */
    record Entry(String eventId, String deviceId, long sequence, String driverId, String type, String routeId,
                 String stopId, Instant capturedAt, Instant receivedAt, String outcome, String code, String message,
                 String payloadJson) {
    }
}
