package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;

/**
 * The referral program's reactions to events and time (S322): a friend signs up with a code, an order is placed, a
 * shipment ends, rewards come due. Called by the messaging listeners and the scheduled jobs; every method is idempotent
 * (a replayed event or job run changes nothing).
 */
public interface ReferralProgram {

    /**
     * A customer signed up (identity.user.registered). Records the sign-up device and network, and when a referral
     * code came with it and the program is on, attaches the friend to the code's owner.
     */
    void signedUp(SignUp signUp);

    /**
     * An order was placed (orders.order.placed). Remembers the delivery address for the abuse checks and, for a referred
     * customer whose referral is waiting for a first order, tracks the order's shipments.
     */
    void orderPlaced(OrderPlaced order);

    /** A shipment was delivered or cancelled (orders.shipment.delivered / cancelled): may qualify the referral. */
    void shipmentEnded(ShipmentEnded shipment);

    /** Grants the rewards of referrals whose return window has passed; the number of referrals settled. */
    int grantDue(int batch);

    /** Expires referrals whose friend did not qualify within the validity window; the number expired. */
    int expireStale(int batch);

    /**
     * @param referralCode the code from the sign-up link or form, as typed (may be null)
     * @param deviceHash   hex SHA-256 of the sign-up browser's device cookie, or null
     * @param networkHash  hex SHA-256 of the sign-up network address (truncated to its /24 or /64), or null
     */
    record SignUp(String userId, String firstName, String lastName, String intent, String referralCode,
                  String deviceHash, String networkHash, Instant registeredAt) {
    }

    record OrderPlaced(String orderId, String customerId, long totalMinor, Instant placedAt, String shipToLine1,
                       String shipToLine2, String shipToPostalCode, String shipToCountry, List<Shipment> shipments) {
    }

    record Shipment(String shipmentId, long totalMinor) {
    }

    record ShipmentEnded(String shipmentId, String orderId, String customerId, boolean delivered, Instant at) {
    }
}
