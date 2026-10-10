package com.afrotransact.taxreturns.application;

/** Tax given back on one order line by a refund (positive amounts; booked negative). */
public record ReversalLine(String lineId, long taxableMinor, long taxMinor) {
}
