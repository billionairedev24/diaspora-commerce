package com.afrotransact.seller.domain;

/**
 * What settled (part of) a plan invoice, in the order they are tried: platform {@code CREDIT} (coupons are already off
 * the invoice), the seller's {@code EARNINGS} (only when they chose to pay from sales), the saved {@code CARD} or bank
 * account, and a {@code WAIVER} (a staff waiver or an incentive that makes the period free).
 */
public enum PaymentSource {
    CREDIT,
    EARNINGS,
    CARD,
    WAIVER
}
