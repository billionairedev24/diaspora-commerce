package com.afrotransact.mcp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ShipmentAndMaskingTest {

    private final DefaultShipmentStatusPolicy shipments = new DefaultShipmentStatusPolicy();
    private final DefaultPiiMasker masker = new DefaultPiiMasker();

    private static final String S = "SELLER_FULFILLED";

    @Test
    void sellersMoveTheirShipmentsForward() {
        assertThatCode(() -> shipments.check(S, "CARRIER", "placed", "CONFIRMED", null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> shipments.check(S, "CARRIER", "confirmed", "packed", null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> shipments.check(S, "CARRIER", "packed", "READY", null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> shipments.check(S, "CARRIER", "ready", "SHIPPED", "USPS", "9400111", null)).doesNotThrowAnyException();
        assertThatCode(() -> shipments.check(S, "PICKUP", "ready", "DELIVERED", null, null, "0427")).doesNotThrowAnyException();
    }

    @Test
    void sellersCannotCancelSkipBackOrTouchFulfilmentCentreShipments() {
        assertThatThrownBy(() -> shipments.check(S, "CARRIER", "placed", "CANCELLED", null, null, null))
                .isInstanceOf(McpRuleViolation.class).hasMessageContaining("support");
        assertThatThrownBy(() -> shipments.check("FBA_AFROTRANSACT", "CARRIER", "placed", "PACKED", null, null, null))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.not-seller-fulfilled"));
        assertThatThrownBy(() -> shipments.check(S, "CARRIER", "delivered", "PACKED", null, null, null))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.invalid-transition"));
        assertThatThrownBy(() -> shipments.check(S, "CARRIER", "confirmed", "READY", null, null, null))
                .as("no skipping packing").satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.invalid-transition"));
        assertThatThrownBy(() -> shipments.check(S, "CARRIER", "packed", "SHIPPED", "USPS", "9400111", null))
                .as("must be READY first").satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.invalid-transition"));
        assertThatThrownBy(() -> shipments.check(S, "CARRIER", "ready", "SHIPPED", "USPS", " ", null))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.tracking-required"));
    }

    @Test
    void aPickupOrderIsHandedOverWithTheBuyersCodeAndOnlyAPickupOrder() {
        assertThatThrownBy(() -> shipments.check(S, "PICKUP", "ready", "DELIVERED", null, null, null))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.handover-code-required"));
        assertThatThrownBy(() -> shipments.check(S, "PICKUP", "ready", "DELIVERED", null, null, "12"))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.handover-code-required"));
        assertThatThrownBy(() -> shipments.check(S, "PICKUP", "ready", "SHIPPED", "USPS", "9400111", null))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.invalid-transition"));
        assertThatThrownBy(() -> shipments.check(S, "CARRIER", "ready", "DELIVERED", null, null, "0427"))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.invalid-transition"));
        assertThatThrownBy(() -> shipments.check(S, "AFROTRANSACT_DELIVERY", "ready", "SHIPPED", "USPS", "9400111", null))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.invalid-transition"));
    }

    @Test
    void masksPersonalDataInApplicationDetails() {
        Object masked = masker.mask(Map.of(
                "legalName", "Ada Foods LLC",
                "taxId", "12-3456789",
                "owner", Map.of("dateOfBirth", "1984-05-17", "email", "ada.obi@example.com",
                        "phone", "+1 (713) 555-0142", "address", Map.of("line1", "1200 Westheimer Rd")),
                "bank", Map.of("accountNumber", 123456789012L),
                "notes", List.of("Call me on 713-555-0142 or ada@example.com")));
        assertThat(masked).isInstanceOf(Map.class);
        String s = masked.toString();
        assertThat(s).contains("legalName=Ada Foods LLC", "taxId=****6789", "dateOfBirth=1984-**-**",
                "email=a***@example.com", "phone=***-***-0142", "line1=*** (masked street address)",
                "accountNumber=****9012", "Call me on ***-***-0142 or a***@example.com");
        assertThat(s).doesNotContain("123456789012", "05-17", "ada.obi@", "Westheimer");
    }
}
