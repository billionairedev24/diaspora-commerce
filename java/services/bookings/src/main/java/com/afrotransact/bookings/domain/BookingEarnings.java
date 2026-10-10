package com.afrotransact.bookings.domain;

/**
 * The money of one booking from the provider's point of view (Earnings screen).
 *
 * @param heldMinor          deposit authorised but not yet captured
 * @param collectedMinor     captured + charged − refunded: what the customer actually paid
 * @param commissionMinor    commission on what was collected only
 * @param cardFeesMinor      card processing on each capture / charge
 * @param providerFeeMinor   late provider-cancellation fee
 * @param netMinor           collected − commission − card fees − provider fee
 * @param balanceDueMinor    balance still to charge on completion (active bookings)
 */
public record BookingEarnings(long heldMinor, long depositCapturedMinor, long balanceChargedMinor, long refundedMinor,
                              long collectedMinor, int commissionBps, long commissionMinor, long cardFeesMinor,
                              long providerFeeMinor, long netMinor, long balanceDueMinor, String currency) {
}
