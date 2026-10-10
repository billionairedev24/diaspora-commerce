package com.afrotransact.taxreturns.domain.returns;

/**
 * The carrier return label of a drop-off RMA (bought through carrier-gateway).
 *
 * @param qrPayload      the carrier's code for a label-free (box-free) drop-off; null for printed labels
 * @param dropOffNetwork where a box-free return is handed over (e.g. "UPS Store")
 */
public record ReturnLabel(String labelId, String carrier, String trackingNumber, String documentUrl,
                          String qrPayload, String dropOffNetwork) {

    /** A printed label. */
    public ReturnLabel(String labelId, String carrier, String trackingNumber, String documentUrl) {
        this(labelId, carrier, trackingNumber, documentUrl, null, null);
    }

    public boolean boxFree() {
        return qrPayload != null && !qrPayload.isBlank();
    }
}
