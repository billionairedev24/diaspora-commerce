package com.afrotransact.orders.port;

/** Marketplace-facilitator sales tax per delivery address (until the tax service exists: a per-state table). */
public interface TaxCalculator {

    Rate rateFor(String country, String state, String postalCode);

    /**
     * Rate in basis points (825 = 8.25 %) and the jurisdiction recorded on each line (e.g. US-TX).
     */
    record Rate(int basisPoints, String jurisdiction) {

        /** Tax of an amount, rounded half up to the minor unit. */
        public long taxOf(long amountMinor) {
            return Math.floorDiv(Math.multiplyExact(amountMinor, (long) basisPoints) + 5_000L, 10_000L);
        }
    }
}
