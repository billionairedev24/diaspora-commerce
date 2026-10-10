package com.afrotransact.payments.application;

import java.time.Instant;

import com.afrotransact.payments.domain.Settlement;
import com.afrotransact.payments.domain.SettlementKind;

/**
 * Settlement hold and transfers: after delivery (products) or completion (services) each seller's net becomes
 * transferable when the hold (7 days by default) has passed; a job then creates the Stripe Connect transfers
 * ({@code transfer_group} = order id).
 */
public interface SettlementService {

    /** Starts the hold (orders / bookings / fulfilment services). Idempotent: the first notice wins. */
    Settlement markDelivered(String orderId, Instant deliveredAt, SettlementKind kind);

    Settlement get(String orderId);

    /** Scheduled job: transfers the matured net of up to {@code maxOrders} orders. Returns transfers created. */
    int releaseMatured(int maxOrders);
}
