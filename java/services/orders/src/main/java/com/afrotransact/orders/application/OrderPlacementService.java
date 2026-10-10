package com.afrotransact.orders.application;

import com.afrotransact.orders.domain.Order;

/** Order persistence steps of the checkout saga (each in one transaction, all idempotent). */
public interface OrderPlacementService {

    /** Stores the order + shipments as PENDING_PAYMENT and records the client secret on the checkout request. */
    void persistPending(Order order, String clientSecret);

    /** Payment authorised: shipments → placed (actor SYSTEM), order PLACED, orders.order.placed. */
    Order place(String orderId);

    /** Voids the checkout: order and shipments CANCELLED with the reason (if it was stored), request FAILED. */
    void abandon(String orderId, String code, String message);
}
