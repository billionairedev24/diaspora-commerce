package com.afrotransact.retail.application;

/** Permission keys checked by retail use cases (granted by identity-service roles). */
public final class RetailPermissions {

    /** View suppliers, items, POs, shipments, receipts, invoices and the GL journal. */
    public static final String READ = "retail:read";
    /** Create and edit 1P items (product master) and their listing readiness — category managers. */
    public static final String ITEMS_WRITE = "retail:items:write";
    /** Create and edit suppliers, change supplier status. */
    public static final String SUPPLIERS_WRITE = "retail:suppliers:write";
    /** Raise, edit, submit, send, confirm and cancel purchase orders — buyers. */
    public static final String PO_WRITE = "retail:po:write";
    /** Approve POs up to the Finance threshold, send back, approve over-receipts — purchasing leads. */
    public static final String PO_APPROVE = "retail:po:approve";
    /** Book shipments, edit ASNs, documents and charges, advance milestones — logistics. */
    public static final String LOGISTICS = "retail:logistics";
    /** Open, count and complete goods receipts — warehouse receivers. */
    public static final String RECEIVE = "retail:receive";
    /** Finance: POs above the threshold, supplier invoices, three-way match, landed cost, closing POs. */
    public static final String FINANCE = "retail:finance";

    private RetailPermissions() {
    }
}
