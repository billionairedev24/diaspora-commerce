package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AreaKind;
import com.afrotransact.deliveryruns.domain.Capacity;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.LocationDetails;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.RunArea;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.DuplicateEntryException;
import com.afrotransact.deliveryruns.port.GeoZoneRegistry;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.deliveryruns.port.SubscriberCounts;
import com.afrotransact.deliveryruns.port.SubscriptionRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LocationServiceImpl implements LocationService {

    private static final long DEFAULT_FEE_MINOR = 799;
    private static final int DEFAULT_MIN_ORDERS = 10;
    private static final int DEFAULT_EXTEND_DAYS = 7;
    private static final int MAX_REASON = 500;
    /** Runs that shoppers have ordered on or that are on the road: the location cannot go off while they exist. */
    private static final Set<RunStatus> LIVE = Set.of(RunStatus.ANNOUNCED, RunStatus.OPEN, RunStatus.EXTENDED,
            RunStatus.CUT_OFF, RunStatus.CONFIRMED, RunStatus.COLLECTING, RunStatus.OUT_FOR_DELIVERY);

    private final LocationRepository locations;
    private final RunRepository runs;
    private final SubscriptionRepository subscriptions;
    private final GeoZoneRegistry zones;
    private final DeliveryRunsEventPublisher events;
    private final PermissionChecker permissions;
    private final Clock clock;

    LocationServiceImpl(LocationRepository locations, RunRepository runs, SubscriptionRepository subscriptions,
                        GeoZoneRegistry zones, DeliveryRunsEventPublisher events, PermissionChecker permissions,
                        Clock clock) {
        this.locations = locations;
        this.runs = runs;
        this.subscriptions = subscriptions;
        this.zones = zones;
        this.events = events;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Location create(LocationCommand command) {
        Actor actor = permissions.require(DeliveryRunsPermissions.MANAGE);
        try {
            Instant now = Instant.now(clock);
            Location created = locations.insert(Location.create(UUID.randomUUID(), details(command, null), now));
            events.locationChanged("CREATED", created, actor.subject(), null, now);
            return created;
        } catch (DuplicateEntryException e) {
            throw new ConflictException("delivery-runs.slug-taken", "Another location uses slug " + command.slug());
        }
    }

    @Override
    @Transactional
    public Location update(UUID id, LocationCommand command) {
        Actor actor = permissions.require(DeliveryRunsPermissions.MANAGE);
        Location current = find(id);
        try {
            Instant now = Instant.now(clock);
            Location updated = locations.update(current.update(details(command, current.active()), now));
            events.locationChanged("UPDATED", updated, actor.subject(), null, now);
            return updated;
        } catch (DuplicateEntryException e) {
            throw new ConflictException("delivery-runs.slug-taken", "Another location uses slug " + command.slug());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Location get(UUID id) {
        permissions.require(DeliveryRunsPermissions.READ);
        return find(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Location> list(Boolean active) {
        permissions.require(DeliveryRunsPermissions.READ);
        return locations.findAll(active);
    }

    @Override
    @Transactional
    public Location deactivate(UUID id) {
        return deactivate(id, null);
    }

    @Override
    @Transactional
    public Location deactivate(UUID id, String reason) {
        Actor actor = permissions.require(DeliveryRunsPermissions.MANAGE);
        Location current = find(id);
        if (!current.active()) {
            return current;
        }
        int live = runs.find(id, LIVE, 1000).size();
        if (live > 0) {
            throw new ConflictException("delivery-runs.location-has-live-runs", current.name() + " has " + live
                    + (live == 1 ? " run" : " runs") + " that announced, take orders or are on the road. Cancel or "
                    + "finish them first");
        }
        Instant now = Instant.now(clock);
        Location off = locations.update(current.deactivate(now));
        events.locationChanged("DEACTIVATED", off, actor.subject(), reasonOf(reason), now);
        return off;
    }

    @Override
    @Transactional
    public Location activate(UUID id, String reason) {
        Actor actor = permissions.require(DeliveryRunsPermissions.MANAGE);
        Location current = find(id);
        if (current.active()) {
            return current;
        }
        Instant now = Instant.now(clock);
        Location on = locations.update(current.activate(now));
        events.locationChanged("ACTIVATED", on, actor.subject(), reasonOf(reason), now);
        return on;
    }

    @Override
    @Transactional(readOnly = true)
    public LocationHistory history(UUID id) {
        permissions.require(DeliveryRunsPermissions.READ);
        Location location = find(id);
        List<SubscriberCounts> counts = subscriptions.counts(id);
        SubscriberCounts subscribers = counts.isEmpty() ? new SubscriberCounts(id, 0, 0, 0, Map.of())
                : counts.getFirst();
        Instant since = Instant.now(clock).minus(366, ChronoUnit.DAYS);
        return new LocationHistory(location, runs.find(id, null, 100), subscribers, subscriptions.growth(id, since));
    }

    private static String reasonOf(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() > MAX_REASON) {
            throw new RuleViolation("delivery-runs.invalid-reason", "The reason is at most " + MAX_REASON
                    + " characters");
        }
        return r.isEmpty() ? null : r;
    }

    private Location find(UUID id) {
        return locations.findById(id).orElseThrow(() -> new NotFoundException("delivery-runs.location-not-found",
                "Location not found"));
    }

    /** {@code keepActive}: an edit never switches a location on or off (that is activate / deactivate, audited). */
    private LocationDetails details(LocationCommand c, Boolean keepActive) {
        if (c.centerLat() == null || c.centerLng() == null) {
            throw new RuleViolation("delivery-runs.invalid-area", "The area needs a centre point (lat, lng)");
        }
        GeoPoint center = new GeoPoint(c.centerLat(), c.centerLng());
        AreaKind kind = c.areaKind() == null ? AreaKind.RADIUS : c.areaKind();
        RunArea area;
        if (kind == AreaKind.POLYGON) {
            String zoneId = c.geoZoneId();
            if ((zoneId == null || zoneId.isBlank()) && c.polygonGeoJson() != null && !c.polygonGeoJson().isBlank()) {
                zoneId = zones.registerPolygon(c.name(), "delivery-runs:" + c.slug(), c.polygonGeoJson());
            }
            area = RunArea.polygon(zoneId, center);
        } else {
            area = RunArea.radius(center, c.radiusMiles() == null ? 0 : c.radiusMiles());
        }
        GeoPoint origin = c.originLat() == null || c.originLng() == null ? null
                : new GeoPoint(c.originLat(), c.originLng());
        return new LocationDetails(c.slug(), c.name(), c.market(), c.state(), zone(c.timeZone()), area, origin,
                c.defaultFeeMinor() == null ? DEFAULT_FEE_MINOR : c.defaultFeeMinor(),
                c.currency() == null ? "USD" : c.currency(),
                c.defaultMinOrders() == null ? DEFAULT_MIN_ORDERS : c.defaultMinOrders(),
                new Capacity(c.defaultCapacityOrders(), c.defaultCapacityPackages()), c.dropMode(), c.pickupPoint(),
                c.whatsappInviteUrl(), c.socialLinks(), c.defaultCutoffPolicy(),
                c.defaultExtendDays() == null ? DEFAULT_EXTEND_DAYS : c.defaultExtendDays(),
                keepActive != null ? keepActive : c.active() == null || c.active());
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id == null || id.isBlank() ? "America/Chicago" : id);
        } catch (DateTimeException e) {
            throw new RuleViolation("delivery-runs.invalid-time-zone", "Unknown time zone " + id);
        }
    }
}
