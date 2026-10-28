package com.afrotransact.deliveryruns.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Projection of an order's pre-order packages for one window (from {@code orders.order.placed},
 * {@code orders.shipment.cancelled}, {@code orders.order.cancelled} and late adds). PII is the minimum dispatch
 * needs and is deleted 90 days after delivery.
 *
 * @param orderId       orders-service order id
 * @param number        window order number: {@code LBK-7-1001…}, late adds {@code LBK-7-L90…}
 * @param windowId      the window
 * @param customerId    identity subject of the customer
 * @param customerName  recipient name
 * @param phone         recipient phone
 * @param email         customer email
 * @param address       delivery address
 * @param deliveryNote  note for the driver
 * @param placedAt      when the order was placed
 * @param channel       attribution channel
 * @param trackingTag   {@code src=…&cr=…&po=…}
 * @param packages      pre-order packages of the order
 * @param fulfilment    door or pickup
 * @param currency      ISO 4217
 * @param status        paid, late add or cancelled
 * @param note          late-add reason and payment note
 * @param createdAt     projected
 * @param updatedAt     last change
 * @param version       optimistic lock
 * @param refund        refund progress once the order was cancelled by a window or run cancellation (else null)
 */
public record PreorderOrder(String orderId, String number, UUID windowId, String customerId, String customerName,
                            String phone, String email, Address address, String deliveryNote, Instant placedAt,
                            AttributionChannel channel, String trackingTag, List<PreorderPackage> packages,
                            PreorderFulfilment fulfilment, String currency, PreorderOrderStatus status, String note,
                            Instant createdAt, Instant updatedAt, long version,
                            OrderRefund refund) {

    /** An order without refund progress. */
    public PreorderOrder(String orderId, String number, UUID windowId, String customerId, String customerName,
                         String phone, String email, Address address, String deliveryNote, Instant placedAt,
                         AttributionChannel channel, String trackingTag, List<PreorderPackage> packages,
                         PreorderFulfilment fulfilment, String currency, PreorderOrderStatus status, String note,
                         Instant createdAt, Instant updatedAt, long version) {
        this(orderId, number, windowId, customerId, customerName, phone, email, address, deliveryNote, placedAt,
                channel, trackingTag, packages, fulfilment, currency, status, note, createdAt, updatedAt, version,
                null);
    }

    /**
     * Delivery address.
     *
     * @param line   street line
     * @param city   city
     * @param region state / region code
     * @param zip    postal code
     */
    public record Address(String line, String city, String region, String zip) {

        public static final Address NONE = new Address(null, null, null, null);
    }

    public PreorderOrder {
        if (orderId == null || orderId.isBlank() || windowId == null || placedAt == null) {
            throw new RuleViolation("delivery-runs.invalid-order", "Order id, window and placed time are required");
        }
        address = address == null ? Address.NONE : address;
        packages = packages == null ? List.of() : List.copyOf(packages);
        channel = channel == null ? AttributionChannel.SITE_DIRECT : channel;
        fulfilment = fulfilment == null ? PreorderFulfilment.DOOR : fulfilment;
        currency = currency == null ? "USD" : currency;
        status = status == null ? PreorderOrderStatus.PAID : status;
    }

    /** Packages that count: the active ones, or all of them once the order is cancelled. */
    private List<PreorderPackage> counted() {
        List<PreorderPackage> active = packages.stream().filter(p -> !p.cancelled()).toList();
        return active.isEmpty() ? packages : active;
    }

    public List<PreorderLine> lines() {
        return counted().stream().flatMap(p -> p.lines().stream()).toList();
    }

    public int units() {
        return lines().stream().mapToInt(PreorderLine::qty).sum();
    }

    /** Pre-order total including the delivery fee. */
    public long totalMinor() {
        return counted().stream().mapToLong(PreorderPackage::totalMinor).sum();
    }

    public long deliveryFeeMinor() {
        return counted().stream().mapToLong(PreorderPackage::deliveryFeeMinor).sum();
    }

    public boolean counts() {
        return status.counts();
    }

    /** One package was cancelled; the order is cancelled when none is left. */
    public PreorderOrder cancelShipment(String shipmentId, Instant now) {
        boolean known = packages.stream().anyMatch(p -> shipmentId.equals(p.shipmentId()));
        if (!known) {
            return this;
        }
        List<PreorderPackage> next = packages.stream()
                .map(p -> shipmentId.equals(p.shipmentId()) ? p.cancel() : p).toList();
        boolean none = next.stream().allMatch(PreorderPackage::cancelled);
        return new PreorderOrder(orderId, number, windowId, customerId, customerName, phone, email, address,
                deliveryNote, placedAt, channel, trackingTag, next, fulfilment, currency,
                none ? PreorderOrderStatus.CANCELLED : status, note, createdAt, now, version, refund);
    }

    public PreorderOrder cancel(Instant now) {
        List<PreorderPackage> next = packages.stream().map(PreorderPackage::cancel).toList();
        return new PreorderOrder(orderId, number, windowId, customerId, customerName, phone, email, address,
                deliveryNote, placedAt, channel, trackingTag, next, fulfilment, currency, PreorderOrderStatus.CANCELLED,
                note, createdAt, now, version, refund);
    }

    /**
     * A late add recorded from the orders-service response is completed by the order's {@code placed} event:
     * contact, address and packages come from the event; number, channel, tag, status and note stay.
     */
    public PreorderOrder completeWith(PreorderOrder placed, Instant now) {
        return new PreorderOrder(orderId, number, windowId, pick(customerId, placed.customerId),
                pick(placed.customerName, customerName), pick(placed.phone, phone), pick(placed.email, email),
                placed.address.line() != null ? placed.address : address, pick(placed.deliveryNote, deliveryNote),
                placedAt, channel, trackingTag, placed.packages.isEmpty() ? packages : placed.packages,
                placed.fulfilment, placed.currency, status, note, createdAt, now, version, refund);
    }

    private static String pick(String preferred, String other) {
        return preferred != null && !preferred.isBlank() ? preferred : other;
    }
}
