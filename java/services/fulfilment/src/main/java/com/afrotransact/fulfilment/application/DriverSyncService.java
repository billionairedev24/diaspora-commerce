package com.afrotransact.fulfilment.application;

import java.util.List;

/**
 * Offline-first sync (S087). The app sends its queued events in batches; each event is applied at most once (event
 * id), strictly in per-device sequence order, in its own transaction; rule failures and state conflicts are reported
 * per event without blocking the rest. SOS events are raised before anything else, even behind a sequence gap.
 */
public interface DriverSyncService {

    SyncResult sync(String deviceId, List<DriverEvent> events, DriverCredential credential);

    /** Immediate SOS outside the queue (same event id as the queued copy, so it is raised once). */
    EventResult sos(String deviceId, DriverEvent event, DriverCredential credential);
}
