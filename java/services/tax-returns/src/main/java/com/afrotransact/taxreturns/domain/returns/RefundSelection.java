package com.afrotransact.taxreturns.domain.returns;

/** Units of a line to refund and whether the seller is at fault (shipping refund, no restocking fee). */
public record RefundSelection(String lineId, int quantity, boolean sellerFault) {
}
