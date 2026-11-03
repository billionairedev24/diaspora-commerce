package com.afrotransact.seller.domain;

/** What a billing grant did: reward types plus the staff-only free months, waived invoice, trial end and charge gate. */
public enum GrantType {
    FREE_DAYS,
    FREE_MONTHS,
    CREDIT,
    PERCENT_OFF,
    WAIVE_INVOICE,
    TRIAL_END,
    CHARGE_GATE;

    public static GrantType of(RewardType t) {
        return switch (t) {
            case FREE_DAYS -> FREE_DAYS;
            case CREDIT -> CREDIT;
            case PERCENT_OFF -> PERCENT_OFF;
        };
    }
}
