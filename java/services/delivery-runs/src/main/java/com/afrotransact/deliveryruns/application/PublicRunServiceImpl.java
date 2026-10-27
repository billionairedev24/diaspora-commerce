package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.deliveryruns.domain.AreaKind;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.FeeMethod;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.FeeSource;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.LocationDetails;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunArea;
import com.afrotransact.deliveryruns.domain.RunTerms;
import com.afrotransact.deliveryruns.port.GeoGateway;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicRunServiceImpl implements PublicRunService {

    private static final Pattern ZIP = Pattern.compile("^\\d{5}$");

    private final LocationRepository locations;
    private final RunRepository runs;
    private final GeoGateway geo;
    private final RunLinks links;
    private final FeeBook fees;
    private final DeliveryRunsSettings settings;
    private final Clock clock;

    PublicRunServiceImpl(LocationRepository locations, RunRepository runs, GeoGateway geo, RunLinks links,
                         FeeBook fees, DeliveryRunsSettings settings, Clock clock) {
        this.locations = locations;
        this.runs = runs;
        this.geo = geo;
        this.links = links;
        this.fees = fees;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AvailableRun> available(String zip, Double lat, Double lng) {
        Optional<GeoPoint> point = point(zip, lat, lng);
        if (point.isEmpty()) {
            return List.of();
        }
        Map<UUID, Location> covering = new HashMap<>();
        List<Location> active = locations.findAll(true);
        List<Location> polygons = active.stream().filter(l -> area(l).kind() == AreaKind.POLYGON).toList();
        if (!polygons.isEmpty()) {
            Set<String> zones = geo.zonesCovering(point.get(), settings.geoZoneType());
            polygons.stream().filter(l -> zones.contains(area(l).geoZoneId())).forEach(l -> covering.put(l.id(), l));
        }
        List<Location> circles = active.stream().filter(l -> area(l).kind() == AreaKind.RADIUS).toList();
        if (!circles.isEmpty()) {
            List<Double> miles = geo.straightLineMiles(point.get(), circles.stream().map(l -> area(l).center())
                    .toList());
            for (int i = 0; i < circles.size(); i++) {
                Double d = i < miles.size() ? miles.get(i) : null;
                if (d != null && area(circles.get(i)).withinRadius(d)) {
                    covering.put(circles.get(i).id(), circles.get(i));
                }
            }
        }
        if (covering.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        List<AvailableRun> out = new ArrayList<>();
        Map<UUID, Optional<FeeSnapshot>> doorRules = new HashMap<>();
        for (Run run : runs.findAccepting(covering.keySet(), now)) {
            Location location = covering.get(run.locationId());
            Optional<FeeSnapshot> doorRule = doorRules.computeIfAbsent(run.locationId(),
                    id -> fees.doorFeeAt(location, run.terms().currency()));
            out.add(new AvailableRun(run, location, run.terms().capacity().ordersLeft(run.reservedOrders()),
                    run.isFull(1), links.runUrl(location, run), feeRule(run, doorRule)));
        }
        out.sort(Comparator.comparing(a -> a.run().terms().cutoffAt()));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Location> locations() {
        return locations.findAll(true);
    }

    @Override
    @Transactional(readOnly = true)
    public LocationPage locationPage(String slug) {
        Location location = locations.findBySlug(slug).filter(Location::active)
                .orElseThrow(() -> new NotFoundException("delivery-runs.location-not-found", "Location not found"));
        String text = "AfroTransact delivers to " + location.name() + " on community delivery runs.";
        return new LocationPage(location, runs.findUpcoming(location.id(), Instant.now(clock)),
                links.locationUrl(location), links.shareLinks(location, text));
    }

    @Override
    @Transactional(readOnly = true)
    public Advertorial advertorial(UUID runId) {
        Run run = runs.findById(runId).filter(r -> r.status().isPublic())
                .orElseThrow(() -> new NotFoundException("delivery-runs.run-not-found", "Run not found"));
        Location location = locations.findById(run.locationId()).filter(Location::active)
                .orElseThrow(() -> new NotFoundException("delivery-runs.run-not-found", "Run not found"));
        LocationDetails d = location.details();
        RunTerms t = run.terms();
        RunArea area = d.area();
        String day = links.deliveryDay(location, run);
        return new Advertorial(run.id(), location.id(), d.slug(), d.name(), d.market(), d.state(), run.status().name(),
                run.acceptsOrders(Instant.now(clock)),
                DateTimeFormatter.ISO_LOCAL_DATE.format(t.window().start().atZone(d.timeZone())), day,
                t.window().start(), t.window().end(), links.windowDisplay(location, run), t.cutoffAt(),
                links.cutoffDisplay(location, run), t.cutoffAt(), t.cutoffAt(), t.feeMinor(), t.currency(),
                RunLinks.money(t.feeMinor(), t.currency()), t.dropMode().name(),
                t.pickupPoint() == null ? null : t.pickupPoint().display(),
                "AfroTransact delivery run to " + d.name() + " · " + day,
                "Preorder by " + links.cutoffDisplay(location, run),
                links.runUrl(location, run, "storefront", "advertorial", "advertorial"), d.whatsappInviteUrl(),
                area.kind(), area.geoZoneId(), area.center().lat(), area.center().lng(), area.radiusMiles());
    }

    /**
     * The rule behind the run's fee (ADR 0005 §3). A doorstep run costs what the AfroTransact delivery rule says for
     * the location's distance unless staff set another fee on the run, which is then the source (the run keeps the fee
     * its customers were quoted); a pickup-point run is priced by the pickup rule the same way.
     */
    private FeeSnapshot feeRule(Run run, Optional<FeeSnapshot> doorRule) {
        RunTerms t = run.terms();
        boolean door = t.dropMode() == DropMode.DOORSTEP;
        Optional<FeeSnapshot> rule = door ? doorRule : Optional.of(fees.current().pickup(t.currency()));
        if (rule.isPresent() && rule.get().amountMinor() == t.feeMinor()) {
            return rule.get();
        }
        FeeMethod method = door ? FeeMethod.AFROTRANSACT_DELIVERY : FeeMethod.PICKUP;
        return new FeeSnapshot(method, rule.map(FeeSnapshot::ruleId).orElse(null),
                rule.map(FeeSnapshot::ruleVersion).orElse(0L), t.currency(), t.feeMinor(), FeeSource.WINDOW_OVERRIDE,
                null, rule.map(FeeSnapshot::distanceMiles).orElse((BigDecimal) null));
    }

    private Optional<GeoPoint> point(String zip, Double lat, Double lng) {
        if (lat != null && lng != null) {
            return Optional.of(new GeoPoint(lat, lng));
        }
        if (zip != null && !zip.isBlank()) {
            String z = zip.trim();
            if (!ZIP.matcher(z).matches()) {
                throw new RuleViolation("delivery-runs.invalid-zip", "ZIP must be 5 digits");
            }
            return geo.locateZip(z);
        }
        throw new RuleViolation("delivery-runs.address-required", "Give a zip or lat and lng");
    }

    private static RunArea area(Location l) {
        return l.details().area();
    }
}
