package com.afrotransact.mcp.domain;

/**
 * Which shipment status changes a seller may make through MCP: only on seller-fulfilled shipments, only forward
 * (PROCESSING → PACKED → SHIPPED → DELIVERED) and SHIPPED needs a carrier and tracking number. Cancellations,
 * refunds and AfroTransact-fulfilled shipments stay with staff.
 */
public interface ShipmentStatusPolicy {

    /** Throws {@link McpRuleViolation} when the change is not allowed. */
    void check(String fulfilment, String currentStatus, String targetStatus, String carrier, String trackingNumber);
}
