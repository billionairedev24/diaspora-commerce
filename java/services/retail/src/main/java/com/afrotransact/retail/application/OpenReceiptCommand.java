package com.afrotransact.retail.application;

import java.util.UUID;

/** Open a goods receipt against a shipment's ASN ({@code shipmentId}) or directly against a PO ({@code poId}). */
public record OpenReceiptCommand(UUID shipmentId, UUID poId, String dock) {
}
