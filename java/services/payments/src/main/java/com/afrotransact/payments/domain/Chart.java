package com.afrotransact.payments.domain;

/**
 * AfroTransact's payments chart of accounts. Some accounts are scoped: one sub-account per seller / provider
 * organisation ({@link Scope#ORG}) or per tax jurisdiction ({@link Scope#JURISDICTION}).
 */
public enum Chart {
    STRIPE_CLEARING("1000", "Stripe clearing (platform balance)", AccountType.ASSET, Scope.NONE),
    CHARGEBACK_RECEIVABLE("1100", "Chargeback receivable", AccountType.ASSET, Scope.NONE),
    PROVIDER_FEES_RECEIVABLE("1200", "Provider fees receivable", AccountType.ASSET, Scope.ORG),
    SELLER_PAYABLE("2000", "Seller / provider payable", AccountType.LIABILITY, Scope.ORG),
    SALES_TAX_PAYABLE("2100", "Sales tax payable (marketplace facilitator)", AccountType.LIABILITY,
            Scope.JURISDICTION),
    CUSTOMER_REFUNDS_PAYABLE("2200", "Customer refunds payable", AccountType.LIABILITY, Scope.NONE),
    CHARGEBACK_RESERVE("2300", "Chargeback reserve withheld from seller", AccountType.LIABILITY, Scope.ORG),
    /**
     * What AfroTransact owes the carriers for labels bought on its platform carrier accounts (USPS, UPS, FedEx, DHL bill
     * AfroTransact, not the seller): credited at the carrier's negotiated cost when a seller is charged for a label,
     * debited back when the label is voided, and cleared when AfroTransact pays the carrier invoice.
     */
    CARRIER_LABELS_PAYABLE("2400", "Carrier labels payable (carrier invoices AfroTransact)", AccountType.LIABILITY,
            Scope.NONE),
    COMMISSION_REVENUE("4000", "Commission revenue", AccountType.REVENUE, Scope.NONE),
    FBA_FEE_REVENUE("4100", "FBA fee revenue", AccountType.REVENUE, Scope.NONE),
    SHIPPING_REVENUE("4200", "AfroTransact shipping revenue", AccountType.REVENUE, Scope.NONE),
    /**
     * Pickup-delivery fees sellers pay for AfroTransact Delivery from their store or warehouse (S255): the seller packs,
     * an AfroTransact driver collects and delivers. The customer's delivery charge of those lines goes to 4200.
     */
    LOCAL_DELIVERY_FEE_REVENUE("4250", "Local delivery fees (AfroTransact Delivery from seller stores)",
            AccountType.REVENUE, Scope.NONE),
    PROVIDER_FEE_REVENUE("4300", "Provider late-cancellation fees", AccountType.REVENUE, Scope.NONE),
    /**
     * AfroTransact Retail (1P, merchant of record) sales: items + shipping of Retail lines net of their Stripe-fee
     * share. Never owed to anyone, never transferred or paid out; lines carry the Retail org for attribution.
     */
    RETAIL_SALES("4400", "AfroTransact Retail sales", AccountType.REVENUE, Scope.NONE),
    /** Handling markup charged to sellers on top of the carrier cost of labels bought through AfroTransact. */
    LABEL_HANDLING_REVENUE("4500", "Shipping label handling revenue", AccountType.REVENUE, Scope.NONE),
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
