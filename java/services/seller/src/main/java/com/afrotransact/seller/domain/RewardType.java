package com.afrotransact.seller.domain;

/**
 * What a reward gives. {@code FREE_DAYS} pushes the subscription's {@code trial_end} out (no charge until then);
 * {@code CREDIT} posts a customer balance credit the next invoices use; {@code PERCENT_OFF} attaches a repeating
 * coupon for N invoices.
 */
public enum RewardType {
    FREE_DAYS,
    CREDIT,
    PERCENT_OFF
}
