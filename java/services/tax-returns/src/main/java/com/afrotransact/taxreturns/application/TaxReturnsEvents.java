package com.afrotransact.taxreturns.application;

/**
 * Event types published to {@code at.taxreturns.events} (documented in
 * contracts/asyncapi/fragments/tax-returns.yaml). RMA events are keyed by RMA id, refund events by refund id, tax
 * events by order id.
 */
public final class TaxReturnsEvents {

    /** Aggregate type: Kafka topic {@code at.taxreturns.events}. */
    public static final String AGGREGATE = "taxreturns";

    public static final String RMA_REQUESTED = "returns.rma.requested";
    public static final String RMA_APPROVED = "returns.rma.approved";
    public static final String RMA_REJECTED = "returns.rma.rejected";
    public static final String RMA_CANCELLED = "returns.rma.cancelled";
    public static final String RMA_LABEL_ISSUED = "returns.rma.label_issued";
    public static final String RMA_IN_TRANSIT = "returns.rma.in_transit";
    public static final String RMA_RECEIVED = "returns.rma.received";
    public static final String RMA_INSPECTED = "returns.rma.inspected";
    public static final String RMA_REFUNDED = "returns.rma.refunded";
    public static final String REFUND_ISSUED = "returns.refund.issued";
    public static final String REFUND_FAILED = "returns.refund.failed";
    public static final String TAX_SALE_RECORDED = "tax.sale.recorded";
    public static final String TAX_REFUND_REVERSED = "tax.refund.reversed";

    private TaxReturnsEvents() {
    }
}
