package com.afrotransact.mcp.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Seller-allowed shipment steps (see {@link ShipmentStatusPolicy}). */
public class DefaultShipmentStatusPolicy implements ShipmentStatusPolicy {

    private static final Map<String, String> NEXT = Map.of(
            "PLACED", "CONFIRMED",
            "CONFIRMED", "PACKED",
            "PACKED", "READY");
    private static final List<String> SELLER_TARGETS = List.of("CONFIRMED", "PACKED", "READY", "SHIPPED", "DELIVERED");
    private static final Pattern CODE = Pattern.compile("\\d{4}");

    @Override
    public void check(String fulfilmentType, String deliveryMethod, String currentStatus, String targetStatus,
                      String carrier, String trackingNumber, String handoverCode) {
        String target = normalise(targetStatus);
        if (!SELLER_TARGETS.contains(target)) {
            throw new McpRuleViolation("mcp.status-not-allowed",
                    "Sellers can only set " + SELLER_TARGETS + "; contact support for " + target);
        }
        if (!"SELLER_FULFILLED".equals(normalise(fulfilmentType))) {
            throw new McpRuleViolation("mcp.not-seller-fulfilled",
                    "AfroTransact-fulfilled shipments are updated by the fulfilment centre");
        }
        String current = normalise(currentStatus);
        String method = normalise(deliveryMethod);
        boolean handoff = "SHIPPED".equals(target) || "DELIVERED".equals(target);
        if (handoff) {
            if (!"READY".equals(current)) {
                throw new McpRuleViolation("mcp.invalid-transition", "A shipment in " + current + " cannot move to "
                        + target + "; it must be READY first");
            }
            if ("SHIPPED".equals(target) && !"CARRIER".equals(method)) {
                throw new McpRuleViolation("mcp.invalid-transition", method.equals("PICKUP")
                        ? "A pickup order is handed over to the buyer (DELIVERED), not shipped"
                        : "AfroTransact Delivery shipments are collected by our drivers");
            }
            if ("DELIVERED".equals(target) && !"PICKUP".equals(method)) {
                throw new McpRuleViolation("mcp.invalid-transition", "Only a pickup order is handed over by the seller; "
                        + "a carrier or driver confirms the delivery of the others");
            }
        } else if (!target.equals(NEXT.get(current))) {
            throw new McpRuleViolation("mcp.invalid-transition", "A shipment in " + current + " cannot move to "
                    + target);
        }
        if ("SHIPPED".equals(target) && (blank(carrier) || blank(trackingNumber))) {
            throw new McpRuleViolation("mcp.tracking-required", "SHIPPED needs a carrier and a tracking number");
        }
        if ("DELIVERED".equals(target) && (handoverCode == null || !CODE.matcher(handoverCode.trim()).matches())) {
            throw new McpRuleViolation("mcp.handover-code-required",
                    "DELIVERED needs the buyer's 4-digit code from their order page");
        }
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
