package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.ShipTo;

/**
 * The order after stock was reserved: packages split into shipments per fulfilment node, shipping fees, promised
 * dates and handover codes (hash and sealed value only — never the plain code).
 *
 * @param reserved true when inventory holds a reservation for this order
 */
public record OrderDraft(String orderId, String orderNumber, String customerId, String workflowId, String currency,
                         ShipTo shipTo, boolean reserved, List<DraftShipment> shipments, Instant createdAt) {

    /** One shipment to create. */
    public record DraftShipment(String id, int seq, String sellerOrgId, String sellerName,
                                FulfilmentType fulfilmentType, String nodeId, DeliveryMethod method,
                                String serviceLevel, String pickupLocationId, DeliveryPreferences preferences,
                                boolean perishable, boolean handoverRequired, String handoverCodeHash,
                                String handoverCodeSealed, long shippingMinor, LocalDate promisedFrom,
                                LocalDate promisedBy, List<CheckoutPlan.Line> lines) {
    }
}
