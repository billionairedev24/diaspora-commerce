package com.afrotransact.seller.domain;

/**
 * The documents a selling rule can ask for. Expiry dates drive the lifecycle (warnings, grace, pause); an invoice must
 * be recent, and liability insurance must name AfroTransact as additional insured.
 */
public enum SellingDocumentKind {
    PURCHASE_INVOICE("Purchase invoice dated within the last 180 days", false, 180, false),
    PHOTOS_ALL_SIDES("Photos of the product from all sides", false, 0, false),
    BRAND_AUTHORIZATION("Brand authorization letter", false, 0, false),
    SAFETY_CERTIFICATE("Safety or lab certificate", false, 0, false),
    PERMIT_LICENCE("Permit or licence (with its expiry date)", true, 0, false),
    LIABILITY_INSURANCE("Liability insurance naming AfroTransact as additional insured (with its expiry date)", true, 0,
            true);

    private final String label;
    private final boolean expiryRequired;
    private final int maxAgeDays;
    private final boolean namesAfroTransact;

    SellingDocumentKind(String label, boolean expiryRequired, int maxAgeDays, boolean namesAfroTransact) {
        this.label = label;
        this.expiryRequired = expiryRequired;
        this.maxAgeDays = maxAgeDays;
        this.namesAfroTransact = namesAfroTransact;
    }

    /** What the seller reads next to the upload field. */
    public String label() {
        return label;
    }

    /** The document runs out and the seller must give its expiry date. */
    public boolean expiryRequired() {
        return expiryRequired;
    }

    /** The document must be dated within this many days (0: no limit). */
    public int maxAgeDays() {
        return maxAgeDays;
    }

    /** The seller confirms the policy names AfroTransact as additional insured. */
    public boolean namesAfroTransact() {
        return namesAfroTransact;
    }

    /** Whether the date the seller gives for this kind is the date it was issued (invoices) rather than its expiry. */
    public boolean dated() {
        return maxAgeDays > 0;
    }
}
