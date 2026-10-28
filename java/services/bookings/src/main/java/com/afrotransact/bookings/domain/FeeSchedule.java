package com.afrotransact.bookings.domain;

/**
 * Fees taken from service bookings, as payments-service applies them to a provider organisation
 * ({@code GET /v1/fee-schedules/effective?kind=SERVICE&category=…&orgId=…}; e.g. connection fee 10 %, card 2.9 % +
 * 30¢). The connection fee is AfroTransact's percentage of each booking's service amount, on top of the provider's
 * plan fee.
 *
 * @param commissionBps  AfroTransact's connection fee on what the customer actually paid
 * @param cardPctBps     card processing percentage per charge
 * @param cardFixedMinor card processing fixed fee per charge
 * @param source         where the fee comes from: OVERRIDE (negotiated), PLAN, CATEGORY, DEFAULT, RETAIL (payments)
 *                       or CONFIG
 *                       (bookings' configured fallback while payments is unavailable)
 * @param version        payments' fee-schedule version, or null for the configured fallback
 */
public record FeeSchedule(int commissionBps, int cardPctBps, long cardFixedMinor, String source, Integer version) {

    /** Source of the configured fallback. */
    public static final String CONFIG = "CONFIG";
    /** AfroTransact Retail: no commission, only card fees. */
    public static final String RETAIL = "RETAIL";

    public FeeSchedule {
        if (commissionBps < 0 || commissionBps > 10_000 || cardPctBps < 0 || cardPctBps > 10_000
                || cardFixedMinor < 0) {
            throw new IllegalArgumentException("Invalid fee schedule");
        }
        source = source == null || source.isBlank() ? CONFIG : source;
    }

    /** Configured fees (fallback). */
    public FeeSchedule(int commissionBps, int cardPctBps, long cardFixedMinor) {
        this(commissionBps, cardPctBps, cardFixedMinor, CONFIG, null);
    }

    public static FeeSchedule defaults() {
        return new FeeSchedule(1000, 290, 30);
    }

    /** {@code amount × bps / 10 000}, rounded half-up to the cent. */
    static long pct(long amountMinor, int bps) {
        return Math.floorDiv(amountMinor * bps + 5_000, 10_000);
    }

    long commissionOn(long collectedMinor) {
        return collectedMinor <= 0 ? 0 : pct(collectedMinor, commissionBps);
    }

    long cardFeeOn(long chargeMinor) {
        return chargeMinor <= 0 ? 0 : pct(chargeMinor, cardPctBps) + cardFixedMinor;
    }
}
