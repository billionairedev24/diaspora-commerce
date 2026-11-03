package com.afrotransact.trustops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class DefaultAuditClassifierTest {

    private final AuditClassifier classifier = new DefaultAuditClassifier();

    @Test
    void roleGrantIsAttributedToTheAdministrator() {
        AuditFacts f = classifier.classify("identity.role.granted", Map.of("userId", "u-1", "roleKey",
                "platform.trust_safety", "actorId", "admin-7", "reason", "New hire", "at", "2026-10-10T08:00:00Z"),
                "u-1").orElseThrow();

        assertThat(f.actorId()).isEqualTo("admin-7");
        assertThat(f.entityType()).isEqualTo("USER");
        assertThat(f.entityId()).isEqualTo("u-1");
        assertThat(f.summary()).isEqualTo("Granted role platform.trust_safety — New hire");
    }

    @Test
    void sellerDecisionsPayoutsAndMcpCalls() {
        assertThat(classifier.classify("seller.application.approved", Map.of("applicationId", "app_1",
                "reference", "APP-30201", "orgId", "org_9", "actorId", "rev-2", "action", "FINAL_APPROVE",
                "status", "APPROVED"), "app_1").orElseThrow())
                .satisfies(f -> {
                    assertThat(f.entityId()).isEqualTo("APP-30201");
                    assertThat(f.orgId()).isEqualTo("org_9");
                    assertThat(f.summary()).contains("FINAL_APPROVE");
                });
        assertThat(classifier.classify("payments.payout.paid", Map.of("payoutRequestId", "po-1", "orgId", "org_9",
                "amountMinor", 12_000, "currency", "USD", "status", "PAID"), "po-1").orElseThrow())
                .satisfies(f -> {
                    assertThat(f.actorId()).isEqualTo("payments-service");
                    assertThat(f.entityType()).isEqualTo("PAYOUT");
                });
        assertThat(classifier.classify("mcp.tool.invoked", Map.of("tool", "refund_order", "toolset", "staff",
                "subject", "staff-3", "clientId", "mcp-client", "outcome", "ok"), "x").orElseThrow())
                .satisfies(f -> {
                    assertThat(f.actorId()).isEqualTo("staff-3");
                    assertThat(f.actorType()).isEqualTo("AI_AGENT");
                    assertThat(f.entityId()).isEqualTo("refund_order");
                });
    }

    @Test
    void onlyStaffShipmentOverridesAreAudited() {
        Map<String, Object> carrier = Map.of("orderNumber", "AT-1", "shipmentId", "s-1", "sellerOrgId", "org_1",
                "from", "in_transit", "to", "delivered", "actorType", "CARRIER", "actorId", "ups");
        assertThat(classifier.classify("orders.shipment.status_changed", carrier, "o-1")).isEmpty();

        Map<String, Object> admin = Map.of("orderNumber", "AT-1", "shipmentId", "s-1", "sellerOrgId", "org_1",
                "from", "in_transit", "to", "delivered", "actorType", "PLATFORM_ADMIN", "actorId", "ops-1",
                "reason", "Customer confirmed by phone");
        assertThat(classifier.classify("orders.shipment.status_changed", admin, "o-1").orElseThrow())
                .satisfies(f -> {
                    assertThat(f.actorId()).isEqualTo("ops-1");
                    assertThat(f.entityType()).isEqualTo("SHIPMENT");
                    assertThat(f.summary()).isEqualTo("Order AT-1 shipment in_transit → delivered — Customer "
                            + "confirmed by phone");
                });
    }

    @Test
    void unknownTypesFallBackToTheKey() {
        AuditFacts f = classifier.classify("content.flag.changed", Map.of("actorId", "pm-1"), "flag-9").orElseThrow();
        assertThat(f.actorId()).isEqualTo("pm-1");
        assertThat(f.entityId()).isEqualTo("flag-9");
        assertThat(classifier.classify("", Map.of(), "k")).isEmpty();
    }

    @Test
    void preorderSwitchesAndCoverageEditsNameTheStaffMember() {
        AuditFacts paused = classifier.classify("preorder.window.paused", Map.of("windowId", "w-1", "code", "LUB-7",
                "name", "Lubbock Pre-order #7", "actorId", "staff-3"), "w-1").orElseThrow();
        assertThat(paused.actorId()).isEqualTo("staff-3");
        assertThat(paused.entityType()).isEqualTo("PREORDER_WINDOW");
        assertThat(paused.summary()).isEqualTo("Switched off Lubbock Pre-order #7 (LUB-7)");
        assertThat(classifier.classify("preorder.window.resumed", Map.of("windowId", "w-1", "code", "LUB-7",
                "name", "Lubbock Pre-order #7", "actorId", "staff-3"), "w-1").orElseThrow().summary())
                .startsWith("Switched on");
        AuditFacts areas = classifier.classify("preorder.areas.changed", Map.of("region", "US/TX", "actorId",
                "staff-3"), "preorder-settings").orElseThrow();
        assertThat(areas.entityId()).isEqualTo("US/TX");
        assertThat(classifier.classify("preorder.settings.changed", Map.of("enabled", false, "actor", "staff-4"),
                "preorder-settings").orElseThrow().summary()).contains("master switch off");
    }

    @Test
    void deliveryLocationSwitchesNameTheStaffMemberAndTheReason() {
        AuditFacts off = classifier.classify("delivery-runs.location.changed", Map.of("locationId", "loc-1", "name",
                "Bastrop", "action", "DEACTIVATED", "actorId", "staff-3", "reason", "Seasonal pause"), "loc-1")
                .orElseThrow();
        assertThat(off.actorId()).isEqualTo("staff-3");
        assertThat(off.entityType()).isEqualTo("DELIVERY_RUN_LOCATION");
        assertThat(off.entityId()).isEqualTo("loc-1");
        assertThat(off.summary()).isEqualTo("Switched off delivery location Bastrop — Seasonal pause");
        assertThat(classifier.classify("delivery-runs.location.changed", Map.of("locationId", "loc-1", "name",
                "Bastrop", "action", "ACTIVATED", "actorId", "staff-3"), "loc-1").orElseThrow().summary())
                .isEqualTo("Switched on delivery location Bastrop");
        assertThat(classifier.classify("delivery-runs.location.changed", Map.of("locationId", "loc-1", "name",
                "Bastrop", "action", "UPDATED", "actorId", "staff-3"), "loc-1").orElseThrow().summary())
                .startsWith("Edited");
    }
}
