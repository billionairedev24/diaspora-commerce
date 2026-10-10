package com.afrotransact.orders.application;

import java.util.Optional;

import com.afrotransact.orders.port.PaymentsClient;

/** Read side: the customer's orders page, order detail per viewer, the seller queue and staff search. */
public interface OrderQueryService {

    /** {@code GET /v1/orders?customer=me}: the calling customer's orders, newest first. */
    OrderPage<OrderView> mine(String cursor, int limit);

    /** {@code GET /v1/orders/{number}} (number or id): customer → own order; seller → own shipments; staff → all. */
    OrderView get(String numberOrId);

    /** {@code GET /v1/seller/shipments}: the seller organisation's shipments, optionally by status. */
    OrderPage<SellerShipmentView> sellerQueue(String sellerOrgId, String status, String cursor, int limit);

    /** {@code GET /v1/admin/orders}: staff search. */
    OrderPage<OrderView> search(OrderSearchQuery query, String cursor, int limit);

    /**
     * Who may follow the live shipment feed: staff with orders:read see every shipment (empty), sellers with
     * orders:read only their organisation's.
     */
    Optional<String> liveFeedScope();

    /** Payment split of an order (staff: every recipient; sellers: their own row). */
    PaymentsClient.Split paymentSplit(String numberOrId);
}
