package com.afrotransact.trustops.domain;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mapping for the audit-worthy events in contracts/asyncapi/events.yaml: identity role and permission grants,
 * seller application decisions and compliance enforcement, payments payouts, MCP tool invocations and order /
 * shipment status overrides by staff. Unknown types fall back to {@code actorId}/{@code subject} and the message key.
 */
public final class DefaultAuditClassifier implements AuditClassifier {

    /** Shipment changes are privileged only when made by staff (an override), not by sellers, carriers or the system. */
    private static final Set<String> OVERRIDE_ACTORS = Set.of("PLATFORM_ADMIN", "FULFILMENT_OPS");

    @Override
    public Optional<AuditFacts> classify(String eventType, Map<String, Object> p, String key) {
        if (eventType == null || eventType.isBlank()) {
            return Optional.empty();
        }
        return switch (eventType) {
            case "identity.role.granted", "identity.role.revoked" -> Optional.of(new AuditFacts(s(p, "actorId"),
                    null, eventType, "USER", s(p, "userId"), s(p, "orgId"),
                    verb(eventType) + " role " + s(p, "roleKey") + reason(p)));
            case "identity.permission.granted", "identity.permission.revoked" -> {
                boolean direct = s(p, "userId") != null;
                yield Optional.of(new AuditFacts(s(p, "actorId"), null, eventType, direct ? "USER" : "ROLE",
                        direct ? s(p, "userId") : s(p, "roleKey"), null,
                        verb(eventType) + " permission " + s(p, "permissionKey") + reason(p)));
            }
            case "identity.user.disabled", "identity.user.enabled" -> Optional.of(new AuditFacts(s(p, "actorId"),
                    null, eventType, "USER", first(s(p, "userId"), key), null, eventType + reason(p)));
            case "seller.application.flagged", "seller.application.info_requested", "seller.application.approved",
                 "seller.application.rejected" -> Optional.of(new AuditFacts(s(p, "actorId"), null, eventType,
                    "APPLICATION", first(s(p, "reference"), s(p, "applicationId")), s(p, "orgId"),
                    "Application " + s(p, "action") + " → " + s(p, "status")
                            + (s(p, "reasonCode") == null ? "" : " (" + s(p, "reasonCode") + ")")));
            case "seller.payouts.paused", "seller.payouts.resumed", "seller.listings.hidden",
                 "seller.listings.restored" -> Optional.of(new AuditFacts(first(s(p, "actorId"), "system"), null,
                    eventType, "ORG", first(s(p, "orgId"), key), first(s(p, "orgId"), key),
                    eventType + " (" + s(p, "reason") + ")"));
            case "payments.payout.requested", "payments.payout.approved", "payments.payout.paid",
                 "payments.payout.failed" -> Optional.of(new AuditFacts(first(s(p, "actorId"), "payments-service"),
                    "SERVICE", eventType, "PAYOUT", first(s(p, "payoutRequestId"), key), s(p, "orgId"),
                    "Payout " + s(p, "status") + " " + s(p, "amountMinor") + " " + s(p, "currency")));
            case "mcp.tool.invoked" -> Optional.of(new AuditFacts(s(p, "subject"), "AI_AGENT", eventType, "MCP_TOOL",
                    s(p, "tool"), s(p, "orgId"), "MCP " + s(p, "toolset") + " tool " + s(p, "tool") + " via "
                    + s(p, "clientId") + ": " + s(p, "outcome")));
            case "orders.shipment.status_changed", "orders.shipment.cancelled" -> override(eventType, p);
            case "preorder.window.paused", "preorder.window.resumed" -> Optional.of(new AuditFacts(
                    first(s(p, "actorId"), "system"), "STAFF", eventType, "PREORDER_WINDOW", first(s(p, "windowId"), key),
                    null, ("preorder.window.paused".equals(eventType) ? "Switched off " : "Switched on ")
                    + s(p, "name") + " (" + s(p, "code") + ")"));
            case "preorder.settings.changed" -> Optional.of(new AuditFacts(first(s(p, "actorId"), s(p, "actor")),
                    "STAFF", eventType, "PREORDER_SETTINGS", key, null,
                    "Pre-order settings changed (master switch " + (Boolean.TRUE.equals(p.get("enabled")) ? "on" : "off")
                    + ")"));
            case "preorder.areas.changed" -> Optional.of(new AuditFacts(s(p, "actorId"), "STAFF", eventType,
                    "PREORDER_AREAS", s(p, "region"), null, "Pre-order coverage of " + s(p, "region") + " edited"));
            case "delivery-runs.location.changed" -> Optional.of(new AuditFacts(s(p, "actorId"), "STAFF", eventType,
                    "DELIVERY_RUN_LOCATION", first(s(p, "locationId"), key), null, locationSummary(p)));
            default -> Optional.of(new AuditFacts(first(s(p, "actorId"), s(p, "subject")), null, eventType, "EVENT",
                    key, s(p, "orgId"), eventType));
        };
    }

    private static Optional<AuditFacts> override(String eventType, Map<String, Object> p) {
        String actorType = s(p, "actorType");
        if (actorType == null || !OVERRIDE_ACTORS.contains(actorType)) {
            return Optional.empty();
        }
        String to = first(s(p, "to"), "cancelled");
        String from = s(p, "from");
        return Optional.of(new AuditFacts(first(s(p, "actorId"), actorType), actorType, eventType, "SHIPMENT",
                s(p, "shipmentId"), s(p, "sellerOrgId"), "Order " + s(p, "orderNumber") + " shipment "
                + (from == null ? "" : from + " → ") + to + reason(p)));
    }

    private static String locationSummary(Map<String, Object> p) {
        String verb = switch (String.valueOf(s(p, "action"))) {
            case "CREATED" -> "Created";
            case "ACTIVATED" -> "Switched on";
            case "DEACTIVATED" -> "Switched off";
            default -> "Edited";
        };
        return verb + " delivery location " + s(p, "name") + reason(p);
    }

    private static String verb(String eventType) {
        return eventType.endsWith(".granted") ? "Granted" : "Revoked";
    }

    private static String reason(Map<String, Object> p) {
        String r = s(p, "reason");
        return r == null ? "" : " — " + r;
    }

    private static String s(Map<String, Object> p, String field) {
        Object v = p == null ? null : p.get(field);
        if (v == null) {
            return null;
        }
        String text = String.valueOf(v);
        return text.isBlank() ? null : text;
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }
}
