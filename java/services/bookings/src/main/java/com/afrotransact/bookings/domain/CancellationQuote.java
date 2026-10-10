package com.afrotransact.bookings.domain;

/**
 * Exactly what happens to the money if the booking is cancelled now — shown to the customer before they confirm.
 *
 * @param refundShareBps        share of the deposit that comes back
 * @param refundMinor           captured deposit refunded to the card
 * @param releaseAuthorisation  nothing was captured yet: the card hold is simply released
 * @param releasedMinor         amount whose hold is released (when releaseAuthorisation)
 * @param keptMinor             deposit kept by the provider per policy
 * @param providerFeeMinor      late provider-cancellation fee (provider cancellations under 24 h)
 */
public record CancellationQuote(Party by, int refundShareBps, long depositMinor, long refundMinor,
                                boolean releaseAuthorisation, long releasedMinor, long keptMinor,
                                long providerFeeMinor, String policyLabel, String explanation, String currency) {
}
