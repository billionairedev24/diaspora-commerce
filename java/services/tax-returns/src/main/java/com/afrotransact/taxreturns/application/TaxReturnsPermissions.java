package com.afrotransact.taxreturns.application;

/** Permission keys checked by tax-returns use cases (granted by identity-service roles and client scopes). */
public final class TaxReturnsPermissions {

    /** Existing key: approve and process returns (sellers for their organisation, staff for all). */
    public static final String RETURNS_MANAGE = "returns:manage";
    /** Existing key: issue refunds (partial refunds, retries). */
    public static final String REFUNDS_ISSUE = "refunds:issue";
    /** Existing key: drivers collecting return parcels. */
    public static final String DELIVERY_OPERATE = "delivery:operate";
    /** Existing key: sellers read their monthly statements (tax collected on their behalf). */
    public static final String PAYOUTS_READ = "payouts:read";
    /** New key: quote tax for a cart or order (orders-service client scope). */
    public static final String TAX_QUOTE = "tax:quote";
    /** New key: tax ledger, liability reports and the rate table. */
    public static final String TAX_READ = "tax:read";
    /** New key: edit the rate table. */
    public static final String TAX_CONFIGURE = "tax:configure";

    /**
     * New key: resolve a category's tax outcome from facts without charging (service clients such as catalog-service,
     * staff previews). The identity client scope {@code tax:classify} must be granted to the catalog-service client.
     */
    public static final String TAX_CLASSIFY = "tax:classify";
    /**
     * New key: approve and reclassify variant classifications, change a profile's status gate and edit the provider code
     * map (Tax Reviewer, Catalog Manager, Super Admin). {@code tax:configure} holders are accepted until identity
     * grants the new key.
     */
    public static final String TAX_CLASSIFY_ADMIN = "tax:classify-admin";

    private TaxReturnsPermissions() {
    }
}
