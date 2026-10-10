package com.afrotransact.retail.application;

/** Event types published by retail-service on {@code at.retail.events} (contracts/asyncapi/fragments/retail.yaml). */
public final class RetailEvents {

    /** Aggregate type of every retail event; the outbox relay publishes to {@code at.retail.events}. */
    public static final String AGGREGATE = "retail";

    public static final String ITEM_CREATED = "retail.item.created";
    public static final String ITEM_UPDATED = "retail.item.updated";
    public static final String ITEM_LISTING_CHANGED = "retail.item.listing-changed";
    public static final String SUPPLIER_CREATED = "retail.supplier.created";
    public static final String SUPPLIER_UPDATED = "retail.supplier.updated";
    public static final String SUPPLIER_STATUS_CHANGED = "retail.supplier.status-changed";
    public static final String PO_CREATED = "retail.po.created";
    public static final String PO_UPDATED = "retail.po.updated";
    public static final String PO_SUBMITTED = "retail.po.submitted";
    public static final String PO_APPROVED = "retail.po.approved";
    public static final String PO_SENT_BACK = "retail.po.sent-back";
    public static final String PO_SENT = "retail.po.sent";
    /** A sent PO reached the supplier on one channel (EMAIL requested from notifications, or EDI 850 transmitted). */
    public static final String PO_DISPATCHED = "retail.po.dispatched";
    /** A sent PO could not be delivered on one channel (attempts exhausted or a permanent problem). */
    public static final String PO_DISPATCH_FAILED = "retail.po.dispatch-failed";
    /**
     * Generic notifications request (notifications-service's notification.requested): the PO email to the supplier
     * contact with a signed link to the PDF in media.
     */
    public static final String NOTIFICATION_REQUESTED = "notification.requested";
    public static final String PO_CONFIRMED = "retail.po.confirmed";
    public static final String PO_CANCELLED = "retail.po.cancelled";
    public static final String PO_RECEIVED = "retail.po.received";
    public static final String PO_CLOSED = "retail.po.closed";
    public static final String SHIPMENT_BOOKED = "retail.shipment.booked";
    public static final String SHIPMENT_UPDATED = "retail.shipment.updated";
    public static final String SHIPMENT_STAGE_CHANGED = "retail.shipment.stage-changed";
    public static final String RECEIPT_OPENED = "retail.receipt.opened";
    public static final String RECEIPT_POSTED = "retail.receipt.posted";
    public static final String DISCREPANCY_RAISED = "retail.receipt.discrepancy-raised";
    public static final String CLAIM_RESOLVED = "retail.receipt.claim-resolved";
    public static final String LANDED_COST_ALLOCATED = "retail.landed-cost.allocated";
    public static final String GL_JOURNAL_POSTED = "retail.gl.journal.posted";
    public static final String INVOICE_MATCHED = "retail.invoice.matched";
    public static final String MATCH_EXCEPTION = "retail.match.exception";
    public static final String INVOICE_HELD = "retail.invoice.held";
    public static final String INVOICE_APPROVED = "retail.invoice.approved";
    public static final String INVOICE_REJECTED = "retail.invoice.rejected";
    public static final String PAYMENT_RELEASED = "retail.payment.released";

    private RetailEvents() {
    }
}
