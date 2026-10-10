package com.afrotransact.fulfilment.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A delivery route (aggregate root): its stops and packages and the driver workflow — inspection, load-out, start,
 * pickups, drop-offs with proof, problems, return scans and end of shift. Every rule failure throws
 * {@link FulfilmentRuleViolation} (the driver must fix something) or {@link FulfilmentConflict} (the state moved on).
 */
public final class DeliveryRoute {

    private final String id;
    private final String name;
    private final String zone;
    private final LocalDate serviceDate;
    private final String window;
    private final String depotNodeId;
    private String driverId;
    private String vehicleId;
    private RouteStatus status;
    private InspectionResult inspection;
    private boolean blocked;
    private String blockedReason;
    private Integer startOdometer;
    private Integer endOdometer;
    private Instant loadOutCompletedAt;
    private Instant startedAt;
    private Instant endedAt;
    private final int version;
    private final List<RouteStop> stops;
    private final List<RoutePackage> packages;

    public DeliveryRoute(String id, String name, String zone, LocalDate serviceDate, String window,
                         String depotNodeId, String driverId, String vehicleId, RouteStatus status,
                         InspectionResult inspection, boolean blocked, String blockedReason, Integer startOdometer,
                         Integer endOdometer, Instant loadOutCompletedAt, Instant startedAt, Instant endedAt,
                         int version, List<RouteStop> stops, List<RoutePackage> packages) {
        this.id = id;
        this.name = name;
        this.zone = zone;
        this.serviceDate = serviceDate;
        this.window = window;
        this.depotNodeId = depotNodeId;
        this.driverId = driverId;
        this.vehicleId = vehicleId;
        this.status = status;
        this.inspection = inspection;
        this.blocked = blocked;
        this.blockedReason = blockedReason;
        this.startOdometer = startOdometer;
        this.endOdometer = endOdometer;
        this.loadOutCompletedAt = loadOutCompletedAt;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.version = version;
        this.stops = new ArrayList<>(stops);
        this.stops.sort(Comparator.comparingInt(RouteStop::sequence));
        this.packages = new ArrayList<>(packages);
    }

    /** A new route planned by dispatch. */
    public static DeliveryRoute plan(String id, String name, String zone, LocalDate serviceDate, String window,
                                     String depotNodeId) {
        return new DeliveryRoute(id, name, zone, serviceDate, window, depotNodeId, null, null, RouteStatus.PLANNED,
                null, false, null, null, null, null, null, null, 0, List.of(), List.of());
    }

    // ------------------------------------------------------------------ accessors

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String zone() {
        return zone;
    }

    public LocalDate serviceDate() {
        return serviceDate;
    }

    public String window() {
        return window;
    }

    public String depotNodeId() {
        return depotNodeId;
    }

    public String driverId() {
        return driverId;
    }

    public String vehicleId() {
        return vehicleId;
    }

    public RouteStatus status() {
        return status;
    }

    public InspectionResult inspection() {
        return inspection;
    }

    public boolean blocked() {
        return blocked;
    }

    public String blockedReason() {
        return blockedReason;
    }

    public Integer startOdometer() {
        return startOdometer;
    }

    public Integer endOdometer() {
        return endOdometer;
    }

    public Instant loadOutCompletedAt() {
        return loadOutCompletedAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public int version() {
        return version;
    }

    public List<RouteStop> stops() {
        return List.copyOf(stops);
    }

    public List<RoutePackage> packages() {
        return List.copyOf(packages);
    }

    /** Any active package is perishable: the cold box must read 33–40 °F at inspection. */
    public boolean perishable() {
        return packages.stream().anyMatch(p -> p.status().active() && p.perishable());
    }

    public RouteStop stop(String stopId) {
        return stops.stream().filter(s -> s.id().equals(stopId)).findFirst()
                .orElseThrow(() -> new FulfilmentRuleViolation("fulfilment.stop-unknown",
                        "Stop " + stopId + " is not on route " + id));
    }

    public List<RoutePackage> packagesForDrop(String stopId) {
        return packages.stream().filter(p -> stopId.equals(p.dropoffStopId()) && p.status().active()).toList();
    }

    public List<RoutePackage> packagesForPickup(String stopId) {
        return packages.stream().filter(p -> stopId.equals(p.pickupStopId()) && p.status().active()).toList();
    }

    public List<RoutePackage> fcPackages() {
        return packages.stream().filter(p -> p.origin() == PackageOrigin.FC && p.status().active()).toList();
    }

    // ------------------------------------------------------------------ dispatch

    public void assign(String driver, String vehicle) {
        requireNotStarted("assign a driver");
        if (driver == null || driver.isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.driver-required", "Choose a driver");
        }
        this.driverId = driver;
        if (vehicle != null && !vehicle.isBlank()) {
            if (!vehicle.equals(vehicleId)) {
                // A different vehicle needs its own inspection.
                this.inspection = null;
                this.blocked = false;
                this.blockedReason = null;
            }
            this.vehicleId = vehicle;
        }
        this.status = RouteStatus.ASSIGNED;
    }

    public void addStop(RouteStop stop, List<RoutePackage> stopPackages) {
        if (!status.open()) {
            throw new FulfilmentConflict("fulfilment.route-closed", "Route " + id + " is " + status);
        }
        stop.sequence(stops.isEmpty() ? 1 : stops.getLast().sequence() + 1);
        stops.add(stop);
        packages.addAll(stopPackages);
    }

    /** Inserts a pickup stop before the first drop-off (sellers are visited before their customers). */
    public void addPickupStop(RouteStop stop) {
        if (!status.open()) {
            throw new FulfilmentConflict("fulfilment.route-closed", "Route " + id + " is " + status);
        }
        stops.add(stop);
        resequence(true);
    }

    public Optional<RouteStop> openPickupFor(String sellerOrgId) {
        return stops.stream().filter(s -> s.kind() == StopKind.PICKUP && s.status() == StopStatus.PENDING
                && Objects.equals(s.sellerOrgId(), sellerOrgId)).findFirst();
    }

    public void addPackage(RoutePackage p) {
        packages.add(p);
    }

    public void removeStop(String stopId, Instant at) {
        requireNotStarted("remove stops");
        RouteStop s = stop(stopId);
        s.status(StopStatus.CANCELLED, at);
        for (RoutePackage p : packages) {
            if (stopId.equals(p.dropoffStopId()) && p.status().active()) {
                p.status(PackageStatus.CANCELLED, at);
            }
        }
        // A pickup stop without packages left is cancelled too.
        for (RouteStop pickup : stops) {
            if (pickup.kind() == StopKind.PICKUP && pickup.status() == StopStatus.PENDING
                    && packagesForPickup(pickup.id()).isEmpty()) {
                pickup.status(StopStatus.CANCELLED, at);
            }
        }
    }

    public void cancel(String reason, Instant at) {
        if (status == RouteStatus.IN_PROGRESS || status == RouteStatus.COMPLETED) {
            throw new FulfilmentConflict("fulfilment.route-started", "A started route cannot be cancelled; "
                    + "reassign its stops instead");
        }
        this.status = RouteStatus.CANCELLED;
        this.blockedReason = reason;
        for (RouteStop s : stops) {
            if (s.status() == StopStatus.PENDING) {
                s.status(StopStatus.CANCELLED, at);
            }
        }
        for (RoutePackage p : packages) {
            if (p.status() == PackageStatus.EXPECTED) {
                p.status(PackageStatus.CANCELLED, at);
            }
        }
    }

    /**
     * Takes a stop off this route so dispatch can put it on another one. Allowed for pending, retry, failed and
     * skipped stops. Packages still on board must come back to the FC (they are marked failed and scanned back in).
     *
     * @return the packages to deliver on the new route
     */
    public List<RoutePackage> detachForReassignment(String stopId, Instant at) {
        RouteStop s = stop(stopId);
        if (s.kind() != StopKind.DROPOFF) {
            throw new FulfilmentRuleViolation("fulfilment.reassign-dropoffs-only", "Only drop-offs are reassigned");
        }
        if (s.status() != StopStatus.PENDING && s.status() != StopStatus.RETRY && s.status() != StopStatus.FAILED
                && s.status() != StopStatus.SKIPPED) {
            throw new FulfilmentConflict("fulfilment.stop-not-reassignable", "Stop is " + s.status());
        }
        List<RoutePackage> moving = new ArrayList<>();
        for (RoutePackage p : packages) {
            if (!stopId.equals(p.dropoffStopId())) {
                continue;
            }
            switch (p.status()) {
                case LOADED, PICKED_UP -> {
                    p.status(PackageStatus.FAILED, at);
                    moving.add(p);
                }
                case FAILED, TO_PICKUP_POINT, RETURNED, NOT_PICKED_UP -> moving.add(p);
                case EXPECTED -> {
                    p.status(PackageStatus.REASSIGNED, at);
                    moving.add(p);
                }
                default -> {
                    // delivered, missing, cancelled or already moved: nothing to move
                }
            }
        }
        if (moving.isEmpty()) {
            throw new FulfilmentConflict("fulfilment.nothing-to-reassign", "No package of this stop can be moved");
        }
        s.status(StopStatus.REASSIGNED, at);
        return moving;
    }

    public void markReassigned(String stopId, String newStopId, Instant at) {
        stop(stopId).reassignedTo(newStopId, at);
    }

    // ------------------------------------------------------------------ driver workflow

    /** The route belongs to this driver (or throws). */
    public void requireDriver(String driver) {
        if (driverId == null || !driverId.equals(driver)) {
            throw new FulfilmentConflict("fulfilment.not-your-route", "Route " + id + " is not assigned to you");
        }
    }

    public void recordInspection(String vehicle, InspectionOutcome outcome, int odometer, String defectNote) {
        if (status != RouteStatus.ASSIGNED) {
            throw new FulfilmentConflict("fulfilment.inspection-not-expected", "Inspect before the route starts "
                    + "(route is " + status + ")");
        }
        if (vehicleId != null && !vehicleId.equals(vehicle)) {
            throw new FulfilmentConflict("fulfilment.wrong-vehicle", "Route " + id + " uses vehicle " + vehicleId);
        }
        this.vehicleId = vehicle;
        this.inspection = outcome.result();
        this.startOdometer = odometer;
        this.blocked = !outcome.result().allowsRouteStart();
        this.blockedReason = blocked ? "Vehicle defect: " + outcome.criticalFailures()
                + (defectNote == null ? "" : " — " + defectNote) : null;
    }

    /** Dispatch cleared the defect (e.g. swapped the vehicle and the driver re-inspected, or repair confirmed). */
    public void unblock() {
        this.blocked = false;
        this.blockedReason = null;
    }

    public RoutePackage scanLoadOut(String rawLabel, boolean manual, Instant at) {
        if (status != RouteStatus.ASSIGNED && status != RouteStatus.IN_PROGRESS) {
            throw new FulfilmentConflict("fulfilment.loadout-not-expected", "Route is " + status);
        }
        if (inspection == null || blocked) {
            throw new FulfilmentRuleViolation("fulfilment.inspection-required",
                    "Pass the vehicle inspection before load-out");
        }
        String label = PackageLabel.normalise(rawLabel);
        RoutePackage p = packages.stream().filter(x -> x.label().equals(label) && x.origin() == PackageOrigin.FC
                && x.status().active()).findFirst()
                .orElseThrow(() -> new FulfilmentRuleViolation("fulfilment.label-unknown",
                        label + " isn't on this route's FC manifest. Set it aside and tell dispatch."));
        if (p.status() != PackageStatus.EXPECTED) {
            throw new FulfilmentConflict("fulfilment.label-already-scanned", label + " was already scanned");
        }
        p.status(PackageStatus.LOADED, at);
        p.flagManual(manual);
        return p;
    }

    /** @return the packages loaded at the FC (picked_up for orders-service) */
    public List<RoutePackage> completeLoadOut(Instant at) {
        if (status != RouteStatus.ASSIGNED && status != RouteStatus.IN_PROGRESS) {
            throw new FulfilmentConflict("fulfilment.loadout-not-expected", "Route is " + status);
        }
        long missing = fcPackages().stream().filter(p -> p.status() == PackageStatus.EXPECTED).count();
        if (missing > 0) {
            throw new FulfilmentRuleViolation("fulfilment.loadout-incomplete", missing
                    + " label(s) still to scan");
        }
        this.loadOutCompletedAt = at;
        return fcPackages().stream().filter(p -> p.status() == PackageStatus.LOADED).toList();
    }

    /** @return the packages now out for delivery */
    public List<RoutePackage> start(Instant at) {
        if (status != RouteStatus.ASSIGNED) {
            throw new FulfilmentConflict("fulfilment.route-not-startable", "Route is " + status);
        }
        if (inspection == null) {
            throw new FulfilmentRuleViolation("fulfilment.inspection-required", "Pass the vehicle inspection first");
        }
        if (blocked || !inspection.allowsRouteStart()) {
            throw new FulfilmentRuleViolation("fulfilment.route-blocked", blockedReason == null
                    ? "The route is blocked" : blockedReason);
        }
        if (loadOutCompletedAt == null && !fcPackages().isEmpty()) {
            throw new FulfilmentRuleViolation("fulfilment.loadout-required", "Scan the load-out first");
        }
        this.status = RouteStatus.IN_PROGRESS;
        this.startedAt = at;
        return packages.stream().filter(p -> p.status().onBoard()).toList();
    }

    public RouteStop arrive(String stopId, Instant at) {
        requireInProgress();
        RouteStop s = stop(stopId);
        if (s.status() == StopStatus.ARRIVED) {
            return s;
        }
        if (s.status() != StopStatus.PENDING && s.status() != StopStatus.RETRY) {
            throw new FulfilmentConflict("fulfilment.stop-closed", "Stop is " + s.status());
        }
        if (s.kind() == StopKind.DROPOFF) {
            for (RoutePackage p : packagesForDrop(stopId)) {
                if (p.status() == PackageStatus.EXPECTED) {
                    throw new FulfilmentRuleViolation("fulfilment.package-not-on-board", p.origin()
                            == PackageOrigin.SELLER ? "Pick up from the seller first — a package for this stop is "
                            + "still at their shop" : "Load " + p.label() + " at the FC first");
                }
            }
        }
        s.status(StopStatus.ARRIVED, at);
        return s;
    }

    public RoutePackage scanAtStop(String stopId, String rawLabel, boolean manual, Instant at) {
        RouteStop s = arrived(stopId);
        String label = PackageLabel.normalise(rawLabel);
        if (s.kind() == StopKind.PICKUP) {
            RoutePackage p = packagesForPickup(stopId).stream().filter(x -> x.label().equals(label)).findFirst()
                    .orElseThrow(() -> unknownHere(label));
            if (p.pickupScanned()) {
                throw new FulfilmentConflict("fulfilment.label-already-scanned", label + " was already scanned");
            }
            p.pickupScanned(true, manual, at);
            return p;
        }
        RoutePackage p = packagesForDrop(stopId).stream().filter(x -> x.label().equals(label)).findFirst()
                .orElseThrow(() -> unknownHere(label));
        if (p.dropScanned()) {
            throw new FulfilmentConflict("fulfilment.label-already-scanned", label + " was already scanned");
        }
        if (!p.status().onBoard()) {
            throw new FulfilmentRuleViolation("fulfilment.package-not-on-board", label + " is " + p.status());
        }
        p.dropScanned(manual, at);
        return p;
    }

    /** @return the packages collected from the seller */
    public List<RoutePackage> confirmPickup(String stopId, String handedOverBy, String missingReason,
                                            PickupPolicy policy, Instant at) {
        RouteStop s = arrived(stopId);
        requireKind(s, StopKind.PICKUP);
        List<RoutePackage> expected = packagesForPickup(stopId);
        int scanned = (int) expected.stream().filter(RoutePackage::pickupScanned).count();
        policy.checkConfirmation(handedOverBy, expected.size(), scanned, missingReason, s.partialAccepted());
        List<RoutePackage> collected = new ArrayList<>();
        for (RoutePackage p : expected) {
            if (p.pickupScanned()) {
                p.status(PackageStatus.PICKED_UP, at);
                collected.add(p);
            } else {
                p.status(PackageStatus.NOT_PICKED_UP, at);
            }
        }
        s.handedOverBy(handedOverBy.trim());
        String partial = scanned < expected.size() ? " · " + (expected.size() - scanned) + " not collected: "
                + (missingReason == null || missingReason.isBlank() ? s.note() : missingReason.trim()) : "";
        s.note(scanned + " packages · handed over by " + handedOverBy.trim() + partial, at);
        s.status(StopStatus.DONE, at);
        skipUndeliverableDropoffs(at);
        return collected;
    }

    public StopOutcome pickupProblem(String stopId, PickupProblem problem, PickupPolicy policy, Instant at) {
        RouteStop s = arrived(stopId);
        requireKind(s, StopKind.PICKUP);
        StopOutcome outcome = policy.decide(problem);
        String note = problem.type() + " · " + problem.resolution() + " · " + problem.note().trim();
        switch (outcome) {
            case SKIPPED -> {
                for (RoutePackage p : packagesForPickup(stopId)) {
                    p.pickupScanned(false, false, at);
                    p.status(PackageStatus.NOT_PICKED_UP, at);
                }
                s.note(note, at);
                s.status(StopStatus.SKIPPED, at);
                skipUndeliverableDropoffs(at);
            }
            case CONTINUE -> {
                if (problem.resolution() == PickupResolution.PARTIAL) {
                    s.partialAccepted(note, at);
                } else {
                    s.note(note, at);
                }
            }
            default -> throw new IllegalStateException("Unexpected pickup outcome " + outcome);
        }
        return outcome;
    }

    public void logContact(String stopId, ContactKind kind, Instant at) {
        RouteStop s = arrived(stopId);
        requireKind(s, StopKind.DROPOFF);
        if (kind == null || at == null) {
            throw new FulfilmentRuleViolation("fulfilment.contact-invalid", "Contact kind and time are required");
        }
        s.contact(new ContactAttempt(kind, at));
    }

    /** Facts the proof-of-delivery policy checks for an arrived drop-off. */
    public DeliveryStopFacts deliveryFacts(String stopId) {
        RouteStop s = arrived(stopId);
        requireKind(s, StopKind.DROPOFF);
        List<RoutePackage> pk = packagesForDrop(stopId).stream().filter(p -> p.status().onBoard()).toList();
        boolean perishable = pk.stream().anyMatch(RoutePackage::perishable);
        int scanned = (int) pk.stream().filter(RoutePackage::dropScanned).count();
        return new DeliveryStopFacts(id, perishable, s.handoverRequired(), s.safePlaceAllowed() && !perishable,
                s.point(), pk.size(), scanned);
    }

    /** @return the delivered packages */
    public List<RoutePackage> markDelivered(String stopId, HandoverMethod method, PodAssessment assessment,
                                            String note, Instant at) {
        RouteStop s = arrived(stopId);
        requireKind(s, StopKind.DROPOFF);
        List<RoutePackage> delivered = new ArrayList<>();
        for (RoutePackage p : packagesForDrop(stopId)) {
            if (p.status().onBoard()) {
                p.status(PackageStatus.DELIVERED, at);
                delivered.add(p);
            }
        }
        s.delivered(method, assessment == null ? null : assessment.distanceMeters(), note, at);
        return delivered;
    }

    public ProblemDecision reportProblem(String stopId, DeliveryProblem problem, ReattemptPolicy policy,
                                         Instant at) {
        RouteStop s = arrived(stopId);
        requireKind(s, StopKind.DROPOFF);
        ProblemDecision d = policy.decide(problem, s.contacts(), s.reattempts());
        String note = problem.type() + " → " + problem.resolution()
                + (problem.note() == null || problem.note().isBlank() ? "" : " · " + problem.note().trim());
        switch (d.stopOutcome()) {
            case RETRY -> s.retry(note, at);
            case FAILED -> {
                for (RoutePackage p : packagesForDrop(stopId)) {
                    if (p.status().onBoard()) {
                        p.status(d.packageStatus(), at);
                    }
                }
                s.note(note, at);
                s.status(StopStatus.FAILED, at);
            }
            case CONTINUE -> s.note(note, at);
            default -> throw new IllegalStateException("Unexpected outcome " + d.stopOutcome());
        }
        return d;
    }

    public RoutePackage scanReturn(String rawLabel, boolean manual, Instant at) {
        requireInProgress();
        String label = PackageLabel.normalise(rawLabel);
        RoutePackage p = packages.stream().filter(x -> x.label().equals(label)).findFirst()
                .orElseThrow(() -> new FulfilmentRuleViolation("fulfilment.label-unknown",
                        label + " isn't on this route"));
        if (p.status() == PackageStatus.RETURNED) {
            throw new FulfilmentConflict("fulfilment.label-already-scanned", label + " was already scanned in");
        }
        if (!p.status().awaitingReturnScan()) {
            throw new FulfilmentRuleViolation("fulfilment.label-not-returning", label + " is " + p.status()
                    + ", not a failed package");
        }
        p.status(PackageStatus.RETURNED, at);
        p.flagManual(manual);
        return p;
    }

    public void endShift(Integer odometer, Instant at) {
        requireInProgress();
        long open = stops.stream().filter(s -> s.status().unresolved()).count();
        if (open > 0) {
            throw new FulfilmentRuleViolation("fulfilment.stops-unresolved", open
                    + " stop(s) not finished; reattempts must be delivered or failed before the shift ends");
        }
        long notBack = packages.stream().filter(p -> p.status().awaitingReturnScan()).count();
        if (notBack > 0) {
            throw new FulfilmentRuleViolation("fulfilment.returns-not-scanned", "Scan " + notBack
                    + " failed package(s) back into " + depotNodeId);
        }
        if (odometer == null || startOdometer != null && odometer <= startOdometer) {
            throw new FulfilmentRuleViolation("fulfilment.odometer-invalid", "End odometer must be more than "
                    + startOdometer);
        }
        this.endOdometer = odometer;
        this.endedAt = at;
        this.status = RouteStatus.COMPLETED;
    }

    // ------------------------------------------------------------------ helpers

    private void skipUndeliverableDropoffs(Instant at) {
        for (RouteStop d : stops) {
            if (d.kind() != StopKind.DROPOFF || d.status() != StopStatus.PENDING) {
                continue;
            }
            List<RoutePackage> pk = packagesForDrop(d.id());
            if (!pk.isEmpty() && pk.stream().allMatch(p -> p.status() == PackageStatus.NOT_PICKED_UP)) {
                d.note("Not collected from the seller; seller reschedules", at);
                d.status(StopStatus.SKIPPED, at);
            }
        }
    }

    private void resequence(boolean pickupsFirst) {
        if (pickupsFirst) {
            stops.sort(Comparator.comparingInt((RouteStop s) -> s.kind() == StopKind.PICKUP
                    && s.status() == StopStatus.PENDING ? 0 : 1).thenComparingInt(RouteStop::sequence));
        }
        int n = 1;
        for (RouteStop s : stops) {
            s.sequence(n++);
        }
    }

    private RouteStop arrived(String stopId) {
        requireInProgress();
        RouteStop s = stop(stopId);
        if (s.status() != StopStatus.ARRIVED) {
            throw new FulfilmentConflict("fulfilment.stop-not-arrived", "Tap “I’ve arrived” first (stop is "
                    + s.status() + ")");
        }
        return s;
    }

    private static void requireKind(RouteStop s, StopKind kind) {
        if (s.kind() != kind) {
            throw new FulfilmentRuleViolation("fulfilment.wrong-stop-kind", "This is a " + s.kind() + " stop");
        }
    }

    private void requireInProgress() {
        if (status != RouteStatus.IN_PROGRESS) {
            throw new FulfilmentConflict("fulfilment.route-not-started", "Start the route first (route is "
                    + status + ")");
        }
    }

    private void requireNotStarted(String what) {
        if (status != RouteStatus.PLANNED && status != RouteStatus.ASSIGNED) {
            throw new FulfilmentConflict("fulfilment.route-started", "Cannot " + what + " on a " + status
                    + " route");
        }
    }

    private static FulfilmentRuleViolation unknownHere(String label) {
        return new FulfilmentRuleViolation("fulfilment.label-unknown",
                label + " isn’t expected here. Set it aside and tell dispatch.");
    }
}
