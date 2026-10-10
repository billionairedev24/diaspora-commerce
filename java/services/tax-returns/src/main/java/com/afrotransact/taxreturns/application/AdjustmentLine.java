package com.afrotransact.taxreturns.application;

/** A partial refund of an amount of a line's items and shipping (tax follows proportionally). */
public record AdjustmentLine(String lineId, long itemsMinor, long shippingMinor) {
}
