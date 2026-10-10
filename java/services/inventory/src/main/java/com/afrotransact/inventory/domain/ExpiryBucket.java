package com.afrotransact.inventory.domain;

/** Days until a lot expires (inventory hub overview, S066). */
public enum ExpiryBucket {
    EXPIRED,
    D0_30,
    D31_60,
    D61_90,
    D91_180,
    D181_PLUS,
    NO_EXPIRY;

    /** @param daysLeft days from the node's today to the expiry date; null when the lot has no expiry */
    public static ExpiryBucket of(Long daysLeft) {
        if (daysLeft == null) {
            return NO_EXPIRY;
        }
        if (daysLeft <= 0) {
            return EXPIRED;
        }
        if (daysLeft <= 30) {
            return D0_30;
        }
        if (daysLeft <= 60) {
            return D31_60;
        }
        if (daysLeft <= 90) {
            return D61_90;
        }
        return daysLeft <= 180 ? D91_180 : D181_PLUS;
    }
}
