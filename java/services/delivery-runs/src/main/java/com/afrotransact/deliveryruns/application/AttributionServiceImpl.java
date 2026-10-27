package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AttributionKind;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.UtmTags;
import com.afrotransact.deliveryruns.port.AttributionRepository;
import com.afrotransact.deliveryruns.port.AttributionRepository.Row;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AttributionServiceImpl implements AttributionService {

    /** Different tag sets one location counts per day before the rest go to the overflow bucket. */
    static final int MAX_COMBINATIONS_PER_DAY = 100;
    private static final int MAX_DAYS = 366;

    private final AttributionRepository attribution;
    private final LocationRepository locations;
    private final RunRepository runs;
    private final PermissionChecker permissions;
    private final Clock clock;

    AttributionServiceImpl(AttributionRepository attribution, LocationRepository locations, RunRepository runs,
                           PermissionChecker permissions, Clock clock) {
        this.attribution = attribution;
        this.locations = locations;
        this.runs = runs;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void visit(String locationSlug, UUID runId, UtmTags tags) {
        Location location = locations.findBySlug(locationSlug == null ? "" : locationSlug).filter(Location::active)
                .orElseThrow(() -> new NotFoundException("delivery-runs.location-not-found", "Location not found"));
        if (tags == null || !tags.tagged()) {
            return;
        }
        count(location.id(), ownRun(location.id(), runId), tags, AttributionKind.VISIT);
    }

    @Override
    @Transactional
    public void signupRequested(UUID subscriptionId, UUID locationId, UUID runId, UtmTags tags, boolean active) {
        if (tags == null || !tags.tagged()) {
            return;
        }
        UUID run = ownRun(locationId, runId);
        if (active) {
            count(locationId, run, tags, AttributionKind.SIGNUP);
        } else {
            attribution.remember(subscriptionId, run, tags);
        }
    }

    @Override
    @Transactional
    public void signupActivated(UUID subscriptionId, UUID locationId) {
        attribution.take(subscriptionId).ifPresent(r -> count(locationId, r.runId(), r.tags(), AttributionKind.SIGNUP));
    }

    @Override
    @Transactional
    public void orderPlaced(String orderId, String creative, String src, Instant placedAt) {
        if (orderId == null || orderId.isBlank() || creative == null || !creative.startsWith("adv-")
                || creative.length() > 64 || placedAt == null) {
            return;
        }
        attribution.recordOrder(orderId, creative, src == null || src.length() > 16 ? null : src, placedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Report report(UUID locationId, UUID runId, int days) {
        permissions.require(DeliveryRunsPermissions.READ);
        locations.findById(locationId).orElseThrow(() -> new NotFoundException("delivery-runs.location-not-found",
                "Location not found"));
        LocalDate since = today().minusDays(Math.min(Math.max(days, 1), MAX_DAYS) - 1L);
        List<Row> rows = attribution.rows(locationId, runId, since);
        return new Report(locationId, runId, since, rows.stream().mapToLong(Row::visits).sum(),
                rows.stream().mapToLong(Row::signups).sum(), rows);
    }

    private void count(UUID locationId, UUID runId, UtmTags tags, AttributionKind kind) {
        attribution.count(today(), locationId, runId, tags, kind, MAX_COMBINATIONS_PER_DAY);
    }

    /** The run when it belongs to the location, else null (the visit counts for the location page). */
    private UUID ownRun(UUID locationId, UUID runId) {
        if (runId == null) {
            return null;
        }
        return runs.findById(runId).map(Run::locationId).filter(locationId::equals).map(l -> runId).orElse(null);
    }

    private LocalDate today() {
        return Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
