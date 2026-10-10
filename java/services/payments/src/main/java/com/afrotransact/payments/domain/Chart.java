package com.afrotransact.payments.domain;

/**
 * AfroTransact's payments chart of accounts. Some accounts are scoped: one sub-account per seller / provider
 * organisation ({@link Scope#ORG}) or per tax jurisdiction ({@link Scope#JURISDICTION}).
 */
public enum Chart {
    STRIPE_CLEARING("1000", "Stripe clearing (platform balance)", AccountType.ASSET, Scope.NONE),
    CHARGEBACK_RECEIVABLE("1100", "Chargeback receivable", AccountType.ASSET, Scope.NONE),
    SELLER_PAYABLE("2000", "Seller / provider payable", AccountType.LIABILITY, Scope.ORG),
    SALES_TAX_PAYABLE("2100", "Sales tax payable (marketplace facilitator)", AccountType.LIABILITY,
            Scope.JURISDICTION),
    CUSTOMER_REFUNDS_PAYABLE("2200", "Customer refunds payable", AccountType.LIABILITY, Scope.NONE),
    CHARGEBACK_RESERVE("2300", "Chargeback reserve withheld from seller", AccountType.LIABILITY, Scope.ORG),
    COMMISSION_REVENUE("4000", "Commission revenue", AccountType.REVENUE, Scope.NONE),
    FBA_FEE_REVENUE("4100", "FBA fee revenue", AccountType.REVENUE, Scope.NONE),
    SHIPPING_REVENUE("4200", "AfroTransact shipping revenue", AccountType.REVENUE, Scope.NONE),
    STRIPE_FEES("5000", "Stripe processing fees", AccountType.EXPENSE, Scope.NONE),
    CHARGEBACK_LOSSES("5100", "Chargeback losses", AccountType.EXPENSE, Scope.NONE);

    /** Whether the account has one sub-account per organisation or jurisdiction. */
    public enum Scope { NONE, ORG, JURISDICTION }

    private final String code;
    private final String title;
    private final AccountType type;
    private final Scope scope;

    Chart(String code, String title, AccountType type, Scope scope) {
        this.code = code;
        this.title = title;
        this.type = type;
        this.scope = scope;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public AccountType type() {
        return type;
    }

    public Scope scope() {
        return scope;
    }

    public static Chart ofCode(String code) {
        for (Chart c : values()) {
            if (c.code.equals(code)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown account code " + code);
    }
}
