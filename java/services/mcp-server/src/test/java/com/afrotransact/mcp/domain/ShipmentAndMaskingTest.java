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

    @Test
    void sellersMoveTheirShipmentsForward() {
        assertThatCode(() -> shipments.check("SELLER", "PROCESSING", "packed", null, null)).doesNotThrowAnyException();
        assertThatCode(() -> shipments.check("SELLER", "PACKED", "SHIPPED", "USPS", "9400111")).doesNotThrowAnyException();
        assertThatCode(() -> shipments.check("SELLER", "SHIPPED", "DELIVERED", null, null)).doesNotThrowAnyException();
    }

    @Test
    void sellersCannotCancelSkipBackOrTouchFulfilmentCentreShipments() {
        assertThatThrownBy(() -> shipments.check("SELLER", "PROCESSING", "CANCELLED", null, null))
                .isInstanceOf(McpRuleViolation.class).hasMessageContaining("support");
        assertThatThrownBy(() -> shipments.check("AFROTRANSACT", "PROCESSING", "PACKED", null, null))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.not-seller-fulfilled"));
        assertThatThrownBy(() -> shipments.check("SELLER", "DELIVERED", "PACKED", null, null))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.invalid-transition"));
        assertThatThrownBy(() -> shipments.check("SELLER", "PACKED", "SHIPPED", "USPS", " "))
                .satisfies(e -> assertThat(((McpRuleViolation) e).code()).isEqualTo("mcp.tracking-required"));
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
