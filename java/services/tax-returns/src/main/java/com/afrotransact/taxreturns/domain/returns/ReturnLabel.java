package com.afrotransact.taxreturns.domain.returns;

/** The carrier return label of a drop-off RMA (bought through carrier-gateway). */
public record ReturnLabel(String labelId, String carrier, String trackingNumber, String documentUrl) {
}
