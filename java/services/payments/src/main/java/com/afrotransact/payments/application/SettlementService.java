package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.payments.domain.Settlement;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.domain.SettlementKind;
import com.afrotransact.payments.domain.ShipmentSettlement;

/**
 * Settlement hold and transfers: after delivery (products) or completion (services) each seller's net becomes
 * transferable when the hold (7 days by default) has passed; a job then creates the Stripe Connect transfers
 * ({@code transfer_group} = order id).
 */
public interface SettlementService {

    /** Starts the hold (orders / bookings / fulfilment services). Idempotent: the first notice wins. */
    Settlement markDelivered(String orderId, Instant deliveredAt, SettlementKind kind);

    Settlement get(String orderId);

    /**
     * One shipment of the order was delivered: starts that shipment's hold, so the seller net captured for it is
     * transferred after the hold even while other shipments are on their way. Idempotent per shipment.
     */
    ShipmentSettlement markShipmentDelivered(String orderId, String shipmentId, Instant deliveredAt,
                                             SettlementKind kind);

    /** Shipment holds of an order, oldest delivery first. */
    List<ShipmentSettlement> shipments(String orderId);

    /**
     * Scheduled job: transfers the matured net of up to {@code maxOrders} shipment holds and then of up to
     * {@code maxOrders} order holds. Returns transfers created.
     */
    int releaseMatured(int maxOrders);

    /**
     * Stripe Connect transfers: an organisation's, newest first (its members and staff who see its payouts), or one
     * order's (staff). These are the {@code tr_…} ids to look up in the Stripe dashboard.
     */
    List<SettlementTransfer> transfers(String orgId, String orderId, int limit);
}
