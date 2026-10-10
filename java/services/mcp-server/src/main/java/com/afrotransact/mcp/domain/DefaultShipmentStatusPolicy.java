package com.afrotransact.mcp.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Seller-allowed shipment transitions. */
public class DefaultShipmentStatusPolicy implements ShipmentStatusPolicy {

    private static final Map<String, Set<String>> NEXT = Map.of(
            "PROCESSING", Set.of("PACKED", "SHIPPED"),
            "PACKED", Set.of("SHIPPED"),
            "SHIPPED", Set.of("DELIVERED"));
    private static final List<String> SELLER_TARGETS = List.of("PACKED", "SHIPPED", "DELIVERED");

    @Override
    public void check(String fulfilment, String currentStatus, String targetStatus, String carrier,
                      String trackingNumber) {
        String target = normalise(targetStatus);
        if (!SELLER_TARGETS.contains(target)) {
            throw new McpRuleViolation("mcp.status-not-allowed",
                    "Sellers can only set " + SELLER_TARGETS + "; contact support for " + target);
        }
        if (!"SELLER".equals(normalise(fulfilment))) {
            throw new McpRuleViolation("mcp.not-seller-fulfilled",
                    "AfroTransact-fulfilled shipments are updated by the fulfilment centre");
        }
        String current = normalise(currentStatus);
        if (!NEXT.getOrDefault(current, Set.of()).contains(target)) {
            throw new McpRuleViolation("mcp.invalid-transition", "A shipment in " + current + " cannot move to "
                    + target);
        }
        if ("SHIPPED".equals(target) && (blank(carrier) || blank(trackingNumber))) {
            throw new McpRuleViolation("mcp.tracking-required", "SHIPPED needs a carrier and a tracking number");
        }
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
