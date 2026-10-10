package com.afrotransact.fulfilment.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.afrotransact.fulfilment.domain.DriverAccessStatus;
import com.afrotransact.fulfilment.domain.FulfilmentRuleViolation;
import com.afrotransact.fulfilment.domain.SequencePolicy;
import org.springframework.stereotype.Service;

@Service
class DriverSyncServiceImpl implements DriverSyncService {

    static final int MAX_BATCH = 200;

    private final DriverAccessService access;
    private final DriverEventApplier applier;
    private final SequencePolicy sequences;
    private final DriverLocationService locations;

    DriverSyncServiceImpl(DriverAccessService access, DriverEventApplier applier, SequencePolicy sequences,
                          DriverLocationService locations) {
        this.access = access;
        this.applier = applier;
        this.sequences = sequences;
        this.locations = locations;
    }

    @Override
    public SyncResult sync(String deviceId, List<DriverEvent> events, DriverCredential credential) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.device-required", "deviceId is required");
        }
        if (events.size() > MAX_BATCH) {
            throw new FulfilmentRuleViolation("fulfilment.batch-too-large", "Send at most " + MAX_BATCH
                    + " events per batch");
        }
        DriverContext driver = access.resolve(credential);
        // SOS first: dispatch hears about it even if earlier events are still missing.
        for (DriverEvent e : events) {
            if (e.isSos() && driver.access().acceptsEventCapturedAt(e.capturedAt())) {
                applier.raiseSos(driver, e);
            }
        }
        List<EventResult> results = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();
        boolean held = false;
        for (DriverEvent e : sequences.inOrder(events, DriverEvent::sequence)) {
            if (e.eventId() == null || e.eventId().isBlank()) {
                results.add(new EventResult(e.eventId(), e.sequence(), EventResult.REJECTED,
                        "fulfilment.event-id-required", "Every event needs a client-generated id", false, false));
                continue;
            }
            if (!seenInBatch.add(e.eventId())) {
                continue;
            }
            if (held) {
                results.add(new EventResult(e.eventId(), e.sequence(), EventResult.DEFERRED,
                        "fulfilment.sequence-gap", "Waiting for earlier events", false, false));
                continue;
            }
            EventResult r = applier.apply(driver, deviceId, e, driver.signOut() ? null : credential == null ? null
                    : credential.bearer());
            results.add(r);
            held = EventResult.DEFERRED.equals(r.outcome());
        }
        // S255: the newest GPS fix of the batch is the driver's last known position (nearest-driver search).
        locations.fromSync(driver, deviceId, events);
        long last = applier.lastSequence(deviceId);
        boolean revoked = driver.access().status() == DriverAccessStatus.REVOKED;
        return new SyncResult(deviceId, last, last + 1, driver.signOut(),
                revoked ? driver.access().changedAt() : null, results);
    }

    @Override
    public EventResult sos(String deviceId, DriverEvent event, DriverCredential credential) {
        if (!event.isSos()) {
            throw new FulfilmentRuleViolation("fulfilment.not-sos", "Use POST /v1/driver/sync for " + event.type());
        }
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.event-id-required", "Every event needs a client id");
        }
        DriverContext driver = access.resolve(credential);
        if (!driver.access().acceptsEventCapturedAt(event.capturedAt())) {
            throw DriverAccessServiceImpl.revoked(driver.access());
        }
        boolean raised = applier.raiseSos(driver, event);
        return new EventResult(event.eventId(), event.sequence(), EventResult.APPLIED, null,
                raised ? "Dispatch notified" : "Already raised", !raised, false);
    }
}
