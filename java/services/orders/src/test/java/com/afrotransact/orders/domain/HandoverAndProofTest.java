package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class HandoverAndProofTest {

    private static final HandoverPolicy HANDOVER = new DefaultHandoverPolicy();
    private static final CourierProofPolicy PROOFS = new DefaultCourierProofPolicy(HANDOVER);
    private static final DeliveryPreferences HAND = new DeliveryPreferences(HandoverPreference.HAND, null,
            "Ring twice", ContactPreference.CALL);
    private static final DeliveryPreferences DOOR = DeliveryPreferences.defaults();

    private static CourierProof proof(HandoverMethod method, String code) {
        return new CourierProof("asset-photo", method, code, "Ada", "Neighbour", "asset-signature", "Porch", null,
                29.76, -95.37);
    }

    @Test
    void codeRequiredForPerishablesOrHandDeliveryByOurDriversOrAtPickup() {
        assertThat(HANDOVER.required(true, DeliveryMethod.AFROTRANSACT_DELIVERY, DOOR)).isTrue();
        assertThat(HANDOVER.required(false, DeliveryMethod.AFROTRANSACT_DELIVERY, HAND)).isTrue();
        assertThat(HANDOVER.required(true, DeliveryMethod.PICKUP, DOOR)).isTrue();
        assertThat(HANDOVER.required(false, DeliveryMethod.PICKUP, DOOR)).as("every pickup is handed over by code").isTrue();
        assertThat(HANDOVER.required(false, DeliveryMethod.AFROTRANSACT_DELIVERY, DOOR)).isFalse();
        assertThat(HANDOVER.required(true, DeliveryMethod.CARRIER, HAND)).isFalse();
    }

    @Test
    void codesAreFourDigitsAndOnlyTheHashIsCompared() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = HANDOVER.newCode();
            assertThat(code).matches("\\d{4}");
            seen.add(code);
        }
        assertThat(seen.size()).isGreaterThan(100);
        String hash = HANDOVER.hash("shipment-1", "0427");
        assertThat(hash).hasSize(64).doesNotContain("0427");
        assertThat(HANDOVER.matches("shipment-1", "0427", hash)).isTrue();
        assertThat(HANDOVER.matches("shipment-1", " 0427 ", hash)).isTrue();
        assertThat(HANDOVER.matches("shipment-1", "0428", hash)).isFalse();
        assertThat(HANDOVER.matches("shipment-2", "0427", hash)).isFalse();
        assertThat(HANDOVER.matches("shipment-1", null, hash)).isFalse();
    }

    @Test
    void handingToTheCustomerChecksTheirCode() {
        Shipment s = Shipments.build(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.OUT_FOR_DELIVERY, false, HAND, "4821");
        assertThatCode(() -> PROOFS.check(s, ShipmentStatus.DELIVERED, proof(HandoverMethod.CUSTOMER, "4821")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PROOFS.check(s, ShipmentStatus.DELIVERED, proof(HandoverMethod.CUSTOMER, "1111")))
                .isInstanceOf(OrdersRuleViolation.class)
                .satisfies(e -> assertThat(((OrdersRuleViolation) e).code()).isEqualTo("orders.handover-code-invalid"));
    }

    @Test
    void safePlaceIsRefusedForPerishablesAndHandDelivery() {
        Shipment perishable = Shipments.build(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.OUT_FOR_DELIVERY, true, DOOR, "1234");
        assertThatThrownBy(() -> PROOFS.check(perishable, ShipmentStatus.DELIVERED,
                proof(HandoverMethod.SAFE_PLACE, null)))
                .satisfies(e -> assertThat(((OrdersRuleViolation) e).code()).isEqualTo("orders.safe-place-not-allowed"));
        Shipment hand = Shipments.build(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.OUT_FOR_DELIVERY, false, HAND, "1234");
        assertThatThrownBy(() -> PROOFS.check(hand, ShipmentStatus.DELIVERED, proof(HandoverMethod.SAFE_PLACE, null)))
                .satisfies(e -> assertThat(((OrdersRuleViolation) e).code())
                        .isEqualTo("orders.hand-delivery-required"));
        Shipment ordinary = Shipments.build(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.OUT_FOR_DELIVERY, false, DOOR, null);
        assertThatCode(() -> PROOFS.check(ordinary, ShipmentStatus.DELIVERED, proof(HandoverMethod.SAFE_PLACE, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void deliveryNeedsAPhotoAndSomeoneElseNeedsSignature() {
        Shipment s = Shipments.build(FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY,
                ShipmentStatus.OUT_FOR_DELIVERY, false, DOOR, null);
        assertThatThrownBy(() -> PROOFS.check(s, ShipmentStatus.DELIVERED, new CourierProof(null,
                HandoverMethod.CUSTOMER, null, null, null, null, null, null, null, null)))
                .satisfies(e -> assertThat(((OrdersRuleViolation) e).code()).isEqualTo("orders.proof-photo-required"));
        assertThatThrownBy(() -> PROOFS.check(s, ShipmentStatus.DELIVERED, new CourierProof("photo",
                HandoverMethod.SOMEONE_ELSE, null, "Ada", null, null, null, null, null, null)))
                .satisfies(e -> assertThat(((OrdersRuleViolation) e).code())
                        .isEqualTo("orders.recipient-proof-required"));
        assertThatThrownBy(() -> PROOFS.check(s, ShipmentStatus.DELIVERY_FAILED, CourierProof.none()))
                .satisfies(e -> assertThat(((OrdersRuleViolation) e).code())
                        .isEqualTo("orders.failure-reason-required"));
    }

    @Test
    void redactedProofHidesTheCode() {
        assertThat(proof(HandoverMethod.CUSTOMER, "4821").redacted().handoverCode()).isEqualTo("****");
    }

    @Test
    void instructionsAreLimited() {
        assertThatThrownBy(() -> new DeliveryPreferences(null, null, "x".repeat(241), null))
                .isInstanceOf(OrdersRuleViolation.class);
        assertThat(new DeliveryPreferences(null, " ", " Gate code 1234 ", null).instructions())
                .isEqualTo("Gate code 1234");
    }
}
