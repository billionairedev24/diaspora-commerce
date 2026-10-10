package com.afrotransact.fulfilment.domain;

/**
 * Proof captured at a drop-off.
 *
 * @param method           handover method
 * @param handoverCode     the customer's 4-digit code (never stored; forwarded to orders-service for verification)
 * @param recipientName    who received it when not the customer
 * @param relationship     relationship of the recipient to the customer
 * @param signatureAssetId media asset of the recipient's signature
 * @param safePlace        where it was left
 * @param photo            live photo stamped with time, GPS and route id
 */
public record ProofOfDelivery(HandoverMethod method, String handoverCode, String recipientName, String relationship,
                              String signatureAssetId, String safePlace, PhotoCapture photo) {
}
