package com.afrotransact.payments.application;

/**
 * "Request payout".
 *
 * @param orgId       organisation (staff only; members always request for their own)
 * @param amountMinor amount, or null with {@code all}
 * @param all         request everything available
 */
public record PayoutCommand(String orgId, Long amountMinor, boolean all, String currency) {
}
