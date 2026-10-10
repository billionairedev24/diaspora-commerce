package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.afrotransact.fulfilment.domain.DriverPosition;
import com.afrotransact.fulfilment.domain.FulfilmentRuleViolation;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.port.DriverPositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DriverLocationServiceImpl implements DriverLocationService {

    /** Device clocks drift: fixes up to this far in the future are taken as "now". */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

    private final DriverAccessService access;
    private final DriverPositionRepository positions;
    private final Clock clock;

    DriverLocationServiceImpl(DriverAccessService access, DriverPositionRepository positions, Clock clock) {
        this.access = access;
        this.positions = positions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DriverPosition ping(LocationPing ping, DriverCredential credential) {
        DriverSession session = access.check(credential);
        Instant now = Instant.now(clock);
        Instant at = ping.capturedAt() == null ? now : ping.capturedAt();
        if (at.isAfter(now.plus(CLOCK_SKEW))) {
            throw new FulfilmentRuleViolation("fulfilment.invalid-position", "capturedAt is in the future");
        }
        DriverPosition p = new DriverPosition(session.driverId(), new GeoPoint(ping.lat(), ping.lon()),
                at.isAfter(now) ? now : at, ping.accuracyM(), "PING", ping.deviceId());
        positions.upsertIfNewer(p);
        return positions.find(session.driverId()).orElse(p);
    }

    @Override
    @Transactional
    public void fromSync(DriverContext driver, String deviceId, List<DriverEvent> events) {
        Instant now = Instant.now(clock);
        events.stream()
                .filter(e -> e.lat() != null && e.lon() != null && e.capturedAt() != null)
                .filter(e -> !e.capturedAt().isAfter(now.plus(CLOCK_SKEW)))
                .filter(e -> driver.access().acceptsEventCapturedAt(e.capturedAt()))
                .filter(e -> e.lat() >= -90 && e.lat() <= 90 && e.lon() >= -180 && e.lon() <= 180)
                .max(Comparator.comparing(DriverEvent::capturedAt))
                .ifPresent(e -> positions.upsertIfNewer(new DriverPosition(driver.driverId(),
                        new GeoPoint(e.lat(), e.lon()), e.capturedAt().isAfter(now) ? now : e.capturedAt(), null,
                        "SYNC", deviceId)));
    }
}
