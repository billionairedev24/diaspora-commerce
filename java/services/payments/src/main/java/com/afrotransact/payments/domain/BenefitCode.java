package com.afrotransact.payments.domain;

/**
 * The menu of benefits a membership tier can have (S323, ADR 0006 section 4). A tier lists the ones it has, each with
 * its own numbers; nothing here is a number, the numbers are staff configuration.
 */
public enum BenefitCode {
    /** AfroTransact delivery (stores, pre-order windows, delivery runs) is free above an order minimum, up to a cap. */
    FREE_DELIVERY,
    /** Carrier shipping is free above an order minimum, up to a cap. */
    FREE_SHIPPING,
    /** A percent of the goods spend of a delivered order comes back as AfroTransact credit. */
    CASHBACK,
    /** Member-only prices and deals (the offers hook: documented, not priced here). */
    MEMBER_PRICES,
    /** Members can order from pre-order windows and delivery runs some hours before they open to everyone. */
    EARLY_ACCESS,
    /** Support tools show the member's cases first. */
    PRIORITY_SUPPORT,
    /** Return labels are free for members. */
    FREE_RETURNS,
    /** Referral and gift rewards the member earns are bigger by a percent. */
    BOOSTED_REWARDS,
    /** A fixed amount of credit every month. */
    MONTHLY_CREDIT;

    /** Whether the benefit puts credit in the member's balance (these wait for the first paid period). */
    public boolean grantsCredit() {
        return this == CASHBACK || this == MONTHLY_CREDIT || this == BOOSTED_REWARDS;
    }
}
