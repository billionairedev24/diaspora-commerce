package com.afrotransact.orders.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A sub-order: the lines one seller ships from one fulfilment node with one delivery method. Each shipment has its own
 * status, capture and cancellation.
 *
 * @param id                  shipment id
 * @param orderId             parent order
 * @param seq                 1-based position in the order
 * @param sellerOrgId         selling organisation (AfroTransact Retail for 1P)
 * @param sellerName          seller display name snapshot
 * @param fulfilmentType      who fulfils it
 * @param nodeId              fulfilment centre (AfroTransact-fulfilled) or null (seller location)
 * @param deliveryMethod      carrier, AfroTransact Delivery or pickup
 * @param serviceLevel        e.g. standard, express, same_day, pickup
 * @param pickupLocationId    chosen pickup point (pickup only)
 * @param preferences         handover preference, safe place, instructions, contact
 * @param perishable          contains frozen / chilled food
 * @param handoverRequired    the driver / counter must check the customer's 4-digit code
 * @param handoverCodeHash    SHA-256 of the code (never the code itself)
 * @param handoverCodeSealed  the code encrypted for showing to the customer only
 * @param status              current status
 * @param shippingMinor       shipping charged for this shipment
 * @param carrier             usps / ups / fedex / dhl once a label exists
 * @param trackingNumber      carrier tracking number
 * @param promisedFrom        earliest promised delivery date
 * @param promisedBy          latest promised delivery date
 * @param captureId           payments capture of this shipment
 * @param capturedAt          when it was captured
 * @param deliveredAt         when it was delivered
 * @param lastExternalEventAt time of the newest applied carrier / driver event
 * @param statusChangedAt     time of the last transition
 * @param lines               ordered lines
 * @param version             optimistic lock version
 * @param localDelivery       AfroTransact Delivery from the seller's store (S255): pickup, distance and quote
 *                            snapshot; null for every other shipment
 * @param preorder            the pre-order window of a PREORDER shipment (S272); null for every other shipment
 */
public record Shipment(String id, String orderId, int seq, String sellerOrgId, String sellerName,
                       FulfilmentType fulfilmentType, String nodeId, DeliveryMethod deliveryMethod,
                       String serviceLevel, String pickupLocationId, DeliveryPreferences preferences,
                       boolean perishable, boolean handoverRequired, String handoverCodeHash,
                       String handoverCodeSealed, ShipmentStatus status, long shippingMinor, String carrier,
                       String trackingNumber, LocalDate promisedFrom, LocalDate promisedBy, String captureId,
                       Instant capturedAt, Instant deliveredAt, Instant lastExternalEventAt, Instant statusChangedAt,
                       List<OrderLine> lines, long version, LocalDelivery localDelivery, PreorderPackage preorder) {

    /** A shipment that is not a pre-order. */
    public Shipment(String id, String orderId, int seq, String sellerOrgId, String sellerName,
                    FulfilmentType fulfilmentType, String nodeId, DeliveryMethod deliveryMethod, String serviceLevel,
                    String pickupLocationId, DeliveryPreferences preferences, boolean perishable,
                    boolean handoverRequired, String handoverCodeHash, String handoverCodeSealed,
                    ShipmentStatus status, long shippingMinor, String carrier, String trackingNumber,
                    LocalDate promisedFrom, LocalDate promisedBy, String captureId, Instant capturedAt,
                    Instant deliveredAt, Instant lastExternalEventAt, Instant statusChangedAt, List<OrderLine> lines,
                    long version, LocalDelivery localDelivery) {
        this(id, orderId, seq, sellerOrgId, sellerName, fulfilmentType, nodeId, deliveryMethod, serviceLevel,
                pickupLocationId, preferences, perishable, handoverRequired, handoverCodeHash, handoverCodeSealed,
                status, shippingMinor, carrier, trackingNumber, promisedFrom, promisedBy, captureId, capturedAt,
                deliveredAt, lastExternalEventAt, statusChangedAt, lines, version, localDelivery, null);
    }

    /** A shipment that is not AfroTransact Delivery from a seller's store. */
    public Shipment(String id, String orderId, int seq, String sellerOrgId, String sellerName,
                    FulfilmentType fulfilmentType, String nodeId, DeliveryMethod deliveryMethod, String serviceLevel,
                    String pickupLocationId, DeliveryPreferences preferences, boolean perishable,
                    boolean handoverRequired, String handoverCodeHash, String handoverCodeSealed,
                    ShipmentStatus status, long shippingMinor, String carrier, String trackingNumber,
                    LocalDate promisedFrom, LocalDate promisedBy, String captureId, Instant capturedAt,
                    Instant deliveredAt, Instant lastExternalEventAt, Instant statusChangedAt, List<OrderLine> lines,
                    long version) {
        this(id, orderId, seq, sellerOrgId, sellerName, fulfilmentType, nodeId, deliveryMethod, serviceLevel,
                pickupLocationId, preferences, perishable, handoverRequired, handoverCodeHash, handoverCodeSealed,
                status, shippingMinor, carrier, trackingNumber, promisedFrom, promisedBy, captureId, capturedAt,
                deliveredAt, lastExternalEventAt, statusChangedAt, lines, version, null, null);
    }

    public Shipment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sellerOrgId, "sellerOrgId");
        Objects.requireNonNull(fulfilmentType, "fulfilmentType");
        Objects.requireNonNull(deliveryMethod, "deliveryMethod");
        Objects.requireNonNull(status, "status");
        preferences = preferences == null ? DeliveryPreferences.defaults() : preferences;
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new OrdersRuleViolation("orders.empty-shipment", "A shipment needs at least one line");
        }
        if (shippingMinor < 0) {
            throw new OrdersRuleViolation("orders.invalid-amount", "Shipping cannot be negative");
        }
        if (deliveryMethod == DeliveryMethod.PICKUP && (pickupLocationId == null || pickupLocationId.isBlank())) {
            throw new OrdersRuleViolation("orders.pickup-location-required", "Pickup needs a pickup location");
        }
        if (handoverRequired && (handoverCodeHash == null || handoverCodeHash.isBlank())) {
            throw new IllegalArgumentException("a required handover code needs its hash");
        }
        if (localDelivery != null && (deliveryMethod != DeliveryMethod.AFROTRANSACT_DELIVERY
                || fulfilmentType != FulfilmentType.SELLER_FULFILLED)) {
            throw new IllegalArgumentException("local delivery snapshots belong to seller-packed AfroTransact Delivery");
        }
        if ((preorder != null) != (fulfilmentType == FulfilmentType.PREORDER)) {
            throw new IllegalArgumentException("PREORDER shipments, and only they, carry their pre-order window");
        }
    }

    /** A community pre-order shipment (S272). */
    public boolean preorderShipment() {
        return preorder != null;
    }

    /** The shipment with an updated pre-order snapshot (missed delivery, customer's answer). */
    public Shipment withPreorder(PreorderPackage updated) {
        if (preorder == null) {
            throw new OrdersRuleViolation("orders.not-a-preorder", "Shipment " + id + " is not a pre-order");
        }
        return new Shipment(id, orderId, seq, sellerOrgId, sellerName, fulfilmentType, nodeId, deliveryMethod,
                serviceLevel, pickupLocationId, preferences, perishable, handoverRequired, handoverCodeHash,
                handoverCodeSealed, status, shippingMinor, carrier, trackingNumber, promisedFrom, promisedBy,
                captureId, capturedAt, deliveredAt, lastExternalEventAt, statusChangedAt, lines, version,
                localDelivery, updated);
    }

    /** AfroTransact Delivery from the seller's store (S255): seller-packed, collected by an AfroTransact driver. */
    public boolean deliveredFromSellerStore() {
        return localDelivery != null;
    }

    public long itemsMinor() {
        return lines.stream().mapToLong(OrderLine::itemsMinor).sum();
    }

    public long taxMinor() {
        return lines.stream().mapToLong(OrderLine::taxMinor).sum();
    }

    public long discountMinor() {
        return lines.stream().mapToLong(OrderLine::discountMinor).sum();
    }

    /** What the customer pays for this shipment: items + shipping + tax − discount (the capture amount). */
    public long totalMinor() {
        return itemsMinor() + shippingMinor + taxMinor() - discountMinor();
    }

    public boolean captured() {
        return captureId != null;
    }

    /** Still going to be fulfilled (not cancelled). */
    public boolean live() {
        return status != ShipmentStatus.CANCELLED;
    }

    /**
     * Applies one transition through the state machine. External events (carrier / driver) carry the time they
     * happened; one that is not newer than the last applied external event is ignored, so redelivered or
     * out-of-order scans never move a shipment backwards.
     */
    public TransitionResult transition(ShipmentStatus to, TransitionActor actor, String reason,
                                       TransitionSource source, String eventId, Instant at, Instant externalEventAt,
                                       ShipmentTransitionPolicy policy) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(at, "at");
        boolean external = source == TransitionSource.CARRIER || source == TransitionSource.DRIVER;
        TransitionDecision decision = policy.decide(this, to, actor.kind());
        if (decision.kind() == TransitionDecision.Kind.ALLOW && external && externalEventAt != null
                && lastExternalEventAt != null && !externalEventAt.isAfter(lastExternalEventAt)) {
            decision = TransitionDecision.ignore("stale event: not newer than the last applied scan");
        }
        if (decision.kind() != TransitionDecision.Kind.ALLOW) {
            return new TransitionResult(this, null, decision.kind(), decision.note());
        }
        if ((to == ShipmentStatus.CANCELLED || to == ShipmentStatus.DELIVERY_FAILED)
                && (reason == null || reason.isBlank())) {
            throw new OrdersRuleViolation("orders.reason-required", "A reason is required to set " + to.wire());
        }
        if (to == ShipmentStatus.DELIVERED && source == TransitionSource.API
                && deliveryMethod == DeliveryMethod.AFROTRANSACT_DELIVERY && (reason == null || reason.isBlank())) {
            // Our drivers prove a delivery with a photo and a handover; staff confirming it without them say why.
            throw new OrdersRuleViolation("orders.reason-required",
                    "Say why you are confirming this delivery without the driver's proof");
        }
        Instant eventTime = external && externalEventAt != null ? externalEventAt : at;
        Shipment next = new Shipment(id, orderId, seq, sellerOrgId, sellerName, fulfilmentType, nodeId,
                deliveryMethod, serviceLevel, pickupLocationId, preferences, perishable, handoverRequired,
                handoverCodeHash, handoverCodeSealed, to, shippingMinor, carrier, trackingNumber, promisedFrom,
                promisedBy, captureId, capturedAt, to == ShipmentStatus.DELIVERED ? eventTime : deliveredAt,
                external ? eventTime : lastExternalEventAt, at, lines, version, localDelivery, preorder);
        StatusChange change = new StatusChange(id, eventId, status, to, actor.kind(), actor.id(), actor.orgId(),
                reason == null || reason.isBlank() ? null : reason.trim(), source, eventTime);
        return new TransitionResult(next, change, TransitionDecision.Kind.ALLOW, null);
    }

    /** Records the carrier label so tracking webhooks can be matched to this shipment. */
    public Shipment withTracking(String newCarrier, String newTrackingNumber) {
        if (deliveryMethod != DeliveryMethod.CARRIER) {
            throw new OrdersRuleViolation("orders.not-carrier-shipment", "Only carrier shipments have tracking numbers");
        }
        if (status.terminal()) {
            throw new OrdersStateConflict("orders.shipment-closed", "The shipment is already " + status.wire());
        }
        return new Shipment(id, orderId, seq, sellerOrgId, sellerName, fulfilmentType, nodeId, deliveryMethod,
                serviceLevel, pickupLocationId, preferences, perishable, handoverRequired, handoverCodeHash,
                handoverCodeSealed, status, shippingMinor, newCarrier, newTrackingNumber, promisedFrom, promisedBy,
                captureId, capturedAt, deliveredAt, lastExternalEventAt, statusChangedAt, lines, version,
                localDelivery, preorder);
    }

    /** Marks this shipment's amount captured by payments. */
    public Shipment withCapture(String newCaptureId, Instant at) {
        return new Shipment(id, orderId, seq, sellerOrgId, sellerName, fulfilmentType, nodeId, deliveryMethod,
                serviceLevel, pickupLocationId, preferences, perishable, handoverRequired, handoverCodeHash,
                handoverCodeSealed, status, shippingMinor, carrier, trackingNumber, promisedFrom, promisedBy,
                newCaptureId, at, deliveredAt, lastExternalEventAt, statusChangedAt, lines, version, localDelivery,
                preorder);
    }
}
