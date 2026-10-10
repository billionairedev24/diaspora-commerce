package com.afrotransact.fulfilment.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A seller pickup or customer drop-off on a route. */
public final class RouteStop {

    private final String id;
    private final StopKind kind;
    private int sequence;
    private final String name;
    private final String sellerOrgId;
    private final String orderNumber;
    private final String addressLine;
    private final String city;
    private final String postalCode;
    private final GeoPoint point;
    private final String window;
    private final boolean handoverRequired;
    private final boolean safePlaceAllowed;
    private StopStatus status;
    private int reattempts;
    private final List<ContactAttempt> contacts;
    private boolean partialAccepted;
    private String handedOverBy;
    private String note;
    private HandoverMethod handoverMethod;
    private Double podDistanceMeters;
    private String reassignedTo;
    private Instant updatedAt;

    public RouteStop(String id, StopKind kind, int sequence, String name, String sellerOrgId, String orderNumber,
                     String addressLine, String city, String postalCode, GeoPoint point, String window,
                     boolean handoverRequired, boolean safePlaceAllowed, StopStatus status, int reattempts,
                     List<ContactAttempt> contacts, boolean partialAccepted, String handedOverBy, String note,
                     HandoverMethod handoverMethod, Double podDistanceMeters, String reassignedTo,
                     Instant updatedAt) {
        this.id = id;
        this.kind = kind;
        this.sequence = sequence;
        this.name = name;
        this.sellerOrgId = sellerOrgId;
        this.orderNumber = orderNumber;
        this.addressLine = addressLine;
        this.city = city;
        this.postalCode = postalCode;
        this.point = point;
        this.window = window;
        this.handoverRequired = handoverRequired;
        this.safePlaceAllowed = safePlaceAllowed;
        this.status = status;
        this.reattempts = reattempts;
        this.contacts = new ArrayList<>(contacts == null ? List.of() : contacts);
        this.partialAccepted = partialAccepted;
        this.handedOverBy = handedOverBy;
        this.note = note;
        this.handoverMethod = handoverMethod;
        this.podDistanceMeters = podDistanceMeters;
        this.reassignedTo = reassignedTo;
        this.updatedAt = updatedAt;
    }

    /** A new pending stop. */
    public static RouteStop pending(String id, StopKind kind, int sequence, String name, String sellerOrgId,
                                    String orderNumber, String addressLine, String city, String postalCode,
                                    GeoPoint point, String window, boolean handoverRequired,
                                    boolean safePlaceAllowed, Instant at) {
        return new RouteStop(id, kind, sequence, name, sellerOrgId, orderNumber, addressLine, city, postalCode,
                point, window, handoverRequired, safePlaceAllowed, StopStatus.PENDING, 0, List.of(), false, null,
                null, null, null, null, at);
    }

    public String id() {
        return id;
    }

    public StopKind kind() {
        return kind;
    }

    public int sequence() {
        return sequence;
    }

    public String name() {
        return name;
    }

    public String sellerOrgId() {
        return sellerOrgId;
    }

    public String orderNumber() {
        return orderNumber;
    }

    public String addressLine() {
        return addressLine;
    }

    public String city() {
        return city;
    }

    public String postalCode() {
        return postalCode;
    }

    public GeoPoint point() {
        return point;
    }

    public String window() {
        return window;
    }

    public boolean handoverRequired() {
        return handoverRequired;
    }

    public boolean safePlaceAllowed() {
        return safePlaceAllowed;
    }

    public StopStatus status() {
        return status;
    }

    public int reattempts() {
        return reattempts;
    }

    public List<ContactAttempt> contacts() {
        return List.copyOf(contacts);
    }

    public boolean partialAccepted() {
        return partialAccepted;
    }

    public String handedOverBy() {
        return handedOverBy;
    }

    public String note() {
        return note;
    }

    public HandoverMethod handoverMethod() {
        return handoverMethod;
    }

    public Double podDistanceMeters() {
        return podDistanceMeters;
    }

    public String reassignedTo() {
        return reassignedTo;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    void status(StopStatus next, Instant at) {
        this.status = next;
        this.updatedAt = at;
    }

    void sequence(int seq) {
        this.sequence = seq;
    }

    void retry(String why, Instant at) {
        this.reattempts++;
        this.contacts.clear();
        this.partialAccepted = false;
        this.note = why;
        status(StopStatus.RETRY, at);
    }

    void contact(ContactAttempt c) {
        this.contacts.add(c);
        this.updatedAt = c.at();
    }

    void partialAccepted(String why, Instant at) {
        this.partialAccepted = true;
        this.note = why;
        this.updatedAt = at;
    }

    void note(String text, Instant at) {
        this.note = text;
        this.updatedAt = at;
    }

    void handedOverBy(String who) {
        this.handedOverBy = who;
    }

    void delivered(HandoverMethod method, Double distance, String text, Instant at) {
        this.handoverMethod = method;
        this.podDistanceMeters = distance;
        this.note = text;
        status(StopStatus.DONE, at);
    }

    void reassignedTo(String stopId, Instant at) {
        this.reassignedTo = stopId;
        status(StopStatus.REASSIGNED, at);
    }
}
