package com.afrotransact.fulfilment.domain;

import java.time.Instant;

/** One labelled package on a route: where it is collected, where it is delivered and what happened to it. */
public final class RoutePackage {

    private final String id;
    private final String label;
    private final String shipmentId;
    private final String orderNumber;
    private final PackageOrigin origin;
    private final String pickupStopId;
    private final String dropoffStopId;
    private final boolean perishable;
    private PackageStatus status;
    private boolean pickupScanned;
    private boolean dropScanned;
    private boolean manualEntry;
    private Instant updatedAt;

    public RoutePackage(String id, String label, String shipmentId, String orderNumber, PackageOrigin origin,
                        String pickupStopId, String dropoffStopId, boolean perishable, PackageStatus status,
                        boolean pickupScanned, boolean dropScanned, boolean manualEntry, Instant updatedAt) {
        this.id = id;
        this.label = label;
        this.shipmentId = shipmentId;
        this.orderNumber = orderNumber;
        this.origin = origin;
        this.pickupStopId = pickupStopId;
        this.dropoffStopId = dropoffStopId;
        this.perishable = perishable;
        this.status = status;
        this.pickupScanned = pickupScanned;
        this.dropScanned = dropScanned;
        this.manualEntry = manualEntry;
        this.updatedAt = updatedAt;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String shipmentId() {
        return shipmentId;
    }

    public String orderNumber() {
        return orderNumber;
    }

    public PackageOrigin origin() {
        return origin;
    }

    public String pickupStopId() {
        return pickupStopId;
    }

    public String dropoffStopId() {
        return dropoffStopId;
    }

    public boolean perishable() {
        return perishable;
    }

    public PackageStatus status() {
        return status;
    }

    public boolean pickupScanned() {
        return pickupScanned;
    }

    public boolean dropScanned() {
        return dropScanned;
    }

    /** At least one scan of this package was typed in by hand (flagged for dispatch). */
    public boolean manualEntry() {
        return manualEntry;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    void status(PackageStatus next, Instant at) {
        this.status = next;
        this.updatedAt = at;
    }

    void pickupScanned(boolean scanned, boolean manual, Instant at) {
        this.pickupScanned = scanned;
        this.manualEntry |= manual;
        this.updatedAt = at;
    }

    void dropScanned(boolean manual, Instant at) {
        this.dropScanned = true;
        this.manualEntry |= manual;
        this.updatedAt = at;
    }

    void flagManual(boolean manual) {
        this.manualEntry |= manual;
    }
}
