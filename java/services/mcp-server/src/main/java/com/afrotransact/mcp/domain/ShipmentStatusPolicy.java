package com.afrotransact.mcp.domain;

/**
 * Which shipment steps a seller may take through MCP: the ones orders-service gives a seller, only on seller-fulfilled
 * shipments and only forward: PLACED → CONFIRMED → PACKED → READY, then SHIPPED (handed to the carrier; needs a carrier
 * and a tracking number) on a carrier shipment, or DELIVERED (handed over at the shop; needs the buyer's 4-digit code) on a
 * pickup order. Cancellations, refunds, AfroTransact Delivery and AfroTransact-fulfilled shipments stay with staff.
 * orders-service enforces the same rules; this gives the agent the reason in words before calling it.
 */
public interface ShipmentStatusPolicy {

    /**
     * Throws {@link McpRuleViolation} when the change is not allowed.
     *
     * @param fulfilmentType orders' fulfilmentType: SELLER_FULFILLED | FBA_AFROTRANSACT | RETAIL_1P | PREORDER
     * @param deliveryMethod orders' deliveryMethod: CARRIER | AFROTRANSACT_DELIVERY | PICKUP
     * @param currentStatus  the shipment's status (any case)
     * @param targetStatus   CONFIRMED | PACKED | READY | SHIPPED | DELIVERED (any case)
     * @param carrier        the carrier to record, or the one already on the shipment
     * @param trackingNumber the tracking number to record, or the one already on the shipment
     * @param handoverCode   the buyer's 4-digit code (DELIVERED only)
     */
    void check(String fulfilmentType, String deliveryMethod, String currentStatus, String targetStatus, String carrier,
               String trackingNumber, String handoverCode);
}
