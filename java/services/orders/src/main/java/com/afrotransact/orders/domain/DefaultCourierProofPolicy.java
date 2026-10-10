package com.afrotransact.orders.domain;

/**
 * Proof of delivery rules from the driver app design: a photo always; handed to the customer needs their code when
 * one is required; someone else needs name, relationship and signature; a safe place is never allowed for perishables
 * or hand-delivery orders; a failed attempt needs a reason.
 */
public final class DefaultCourierProofPolicy implements CourierProofPolicy {

    private final HandoverPolicy handover;

    public DefaultCourierProofPolicy(HandoverPolicy handover) {
        this.handover = handover;
    }

    @Override
    public void check(Shipment shipment, ShipmentStatus to, CourierProof proof) {
        CourierProof p = proof == null ? CourierProof.none() : proof;
        if (to == ShipmentStatus.DELIVERY_FAILED && blank(p.failureReason())) {
            throw new OrdersRuleViolation("orders.failure-reason-required", "A failed delivery needs a reason");
        }
        if (to != ShipmentStatus.DELIVERED) {
            return;
        }
        if (blank(p.photoAssetId())) {
            throw new OrdersRuleViolation("orders.proof-photo-required", "A proof-of-delivery photo is required");
        }
        if (p.handoverMethod() == null) {
            throw new OrdersRuleViolation("orders.handover-method-required", "Say how the parcel was handed over");
        }
        switch (p.handoverMethod()) {
            case CUSTOMER -> {
                if (shipment.handoverRequired()
                        && !handover.matches(shipment.id(), p.handoverCode(), shipment.handoverCodeHash())) {
                    throw new OrdersRuleViolation("orders.handover-code-invalid",
                            "The handover code does not match; ask the customer for the 4-digit code");
                }
            }
            case SOMEONE_ELSE -> {
                if (shipment.preferences().handToCustomer()) {
                    throw new OrdersRuleViolation("orders.hand-delivery-required",
                            "The customer asked for hand delivery; hand it to them with their code");
                }
                if (blank(p.recipientName()) || blank(p.relationship()) || blank(p.signatureAssetId())) {
                    throw new OrdersRuleViolation("orders.recipient-proof-required",
                            "Name, relationship and signature of the recipient are required");
                }
            }
            case SAFE_PLACE -> {
                if (shipment.perishable()) {
                    throw new OrdersRuleViolation("orders.safe-place-not-allowed",
                            "Perishable orders cannot be left in a safe place");
                }
                if (shipment.preferences().handToCustomer()) {
                    throw new OrdersRuleViolation("orders.hand-delivery-required",
                            "The customer asked for hand delivery; it cannot be left in a safe place");
                }
                if (blank(p.safePlace())) {
                    throw new OrdersRuleViolation("orders.safe-place-required", "Say where the parcel was left");
                }
            }
            default -> throw new IllegalStateException("Unexpected handover method " + p.handoverMethod());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
