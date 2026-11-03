package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.SignalKind;

/**
 * What the referral program remembers about a referred customer's orders until one qualifies, and the fingerprints
 * compared between referrer and friend.
 */
public interface ReferralTrackingRepository {

    /** Starts tracking an order of a referred customer; false when it is tracked already (a replayed event). */
    boolean trackOrder(UUID referralId, String orderId, Instant placedAt, long totalMinor, List<Shipment> shipments);

    /** The tracked order a shipment belongs to, if any. */
    Optional<String> orderOfShipment(String shipmentId);

    /** Records how a shipment ended. */
    void endShipment(String shipmentId, boolean delivered, Instant at);

    /** Where a tracked order stands. */
    Optional<OrderState> orderState(String orderId);

    /** Marks the order delivered (all shipments ended, at least one delivered). */
    void markQualified(String orderId);

    // ---- signals

    void addSignal(String customerId, SignalKind kind, String hash, Instant now);

    /** Whether the two customers share a fingerprint of this kind. */
    boolean sharedSignal(String customerA, String customerB, SignalKind kind);

    /** Whether the two customers saved the same card (brand, last four, expiry). */
    boolean sharedPaymentMethod(String customerA, String customerB);

    // ---- the payment of an order

    /** Money refunded on the order's payment so far. */
    long refundedMinor(String orderId);

    record Shipment(String shipmentId, long totalMinor) {
    }

    /**
     * @param open            shipments still on their way
     * @param deliveredMinor  value of the delivered shipments
     * @param deliveredAt     when the last shipment was delivered, null if none was
     * @param referralId      the referral the order is tracked for
     */
    record OrderState(UUID referralId, int open, int delivered, long deliveredMinor, Instant deliveredAt) {
    }
}
