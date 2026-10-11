package com.afrotransact.seller.domain;

/** What a billing grant did: reward types plus the staff-only waived invoice and trial end change. */
public enum GrantType {
    FREE_DAYS,
    CREDIT,
    PERCENT_OFF,
    WAIVE_INVOICE,
    TRIAL_END;

    public static GrantType of(RewardType t) {
        return switch (t) {
            case FREE_DAYS -> FREE_DAYS;
            case CREDIT -> CREDIT;
            case PERCENT_OFF -> PERCENT_OFF;
        };
    }
}
