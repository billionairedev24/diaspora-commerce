package com.afrotransact.fulfilment.domain;

import java.util.regex.Pattern;

/**
 * Proof-of-delivery rules from the driver-app design: every package scanned; a live photo stamped with time, GPS and
 * the route id, taken within {@code maxDistanceMeters} (150 m) of the address; handed to the customer needs their
 * 4-digit code when the shipment requires one; someone else needs name, relationship and signature; a safe place is
 * refused for perishables and for customers who did not allow it.
 */
public final class DefaultProofOfDeliveryPolicy implements ProofOfDeliveryPolicy {

    public static final double DEFAULT_MAX_DISTANCE_METERS = 150.0;
    private static final Pattern CODE = Pattern.compile("\\d{4}");

    private final double maxDistanceMeters;

    public DefaultProofOfDeliveryPolicy(double maxDistanceMeters) {
        this.maxDistanceMeters = maxDistanceMeters;
    }

    public DefaultProofOfDeliveryPolicy() {
        this(DEFAULT_MAX_DISTANCE_METERS);
    }

    @Override
    public PodAssessment check(DeliveryStopFacts stop, ProofOfDelivery pod) {
        if (pod == null || pod.method() == null) {
            throw new FulfilmentRuleViolation("fulfilment.handover-method-required", "Say how it was delivered");
        }
        if (stop.scannedPackages() < stop.expectedPackages()) {
            throw new FulfilmentRuleViolation("fulfilment.scan-all-packages", "Scan every package first ("
                    + stop.scannedPackages() + "/" + stop.expectedPackages() + ")");
        }
        switch (pod.method()) {
            case CUSTOMER -> {
                if (stop.handoverRequired() && (pod.handoverCode() == null
                        || !CODE.matcher(pod.handoverCode().trim()).matches())) {
                    throw new FulfilmentRuleViolation("fulfilment.handover-code-required",
                            "Ask the customer for the 4-digit code from their SMS");
                }
            }
            case SOMEONE_ELSE -> {
                if (Text.shorterThan(pod.recipientName(), 2) || Text.blank(pod.relationship())) {
                    throw new FulfilmentRuleViolation("fulfilment.recipient-proof-required",
                            "Enter who received it and their relationship");
                }
                if (Text.blank(pod.signatureAssetId())) {
                    throw new FulfilmentRuleViolation("fulfilment.signature-required", "Ask the recipient to sign");
                }
            }
            case SAFE_PLACE -> {
                if (stop.perishable()) {
                    throw new FulfilmentRuleViolation("fulfilment.safe-place-not-allowed",
                            "Perishable items must be handed to a person");
                }
                if (!stop.safePlaceAllowed()) {
                    throw new FulfilmentRuleViolation("fulfilment.safe-place-not-allowed",
                            "The customer did not allow a safe-place drop");
                }
                if (Text.blank(pod.safePlace())) {
                    throw new FulfilmentRuleViolation("fulfilment.safe-place-required", "Choose where you left it");
                }
            }
            default -> throw new IllegalStateException("Unexpected handover method " + pod.method());
        }
        if (!PhotoCapture.present(pod.photo())) {
            throw new FulfilmentRuleViolation("fulfilment.pod-photo-required", "Take a proof-of-delivery photo");
        }
        pod.photo().requireStamp("Proof-of-delivery", stop.routeId());
        Double distance = null;
        if (stop.address() != null) {
            distance = stop.address().distanceMetersTo(pod.photo().position());
            if (distance > maxDistanceMeters) {
                throw new FulfilmentRuleViolation("fulfilment.pod-too-far", "The photo was taken "
                        + Math.round(distance) + " m from the address; it must be within "
                        + Math.round(maxDistanceMeters) + " m");
            }
        }
        return new PodAssessment(distance);
    }
}
