package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement of a seller's payable (2000:org) on the seller statement: sales, refunds, transfers, chargebacks and
 * shipping labels, each with its signed amount (credit to the seller positive). Label lines carry the label, its
 * shipment and tracking number.
 *
 * @param kind      the journal type (CAPTURE, REFUND, TRANSFER, …, LABEL_CHARGE, LABEL_CREDIT, LABEL_RECOVERY)
 * @param orderId   order of the movement, or null for label charges and credits (not tied to an order's payable)
 * @param commission    product commission AfroTransact took in this movement (positive at capture, negative when a
 *                      refund gave it back); zero for other movements
 * @param connectionFee service connection fee AfroTransact took in this movement (same signs), reported apart from
 *                      commission
 */
public record StatementLine(UUID journalId, JournalType kind, Instant occurredAt, String orderId, String description,
                            Money amount, String labelId, String shipmentId, String trackingNumber, String carrier,
                            Money commission, Money connectionFee) {

    public StatementLine {
        commission = commission == null ? Money.zero(amount.currency()) : commission;
        connectionFee = connectionFee == null ? Money.zero(amount.currency()) : connectionFee;
    }

    /** A movement without commission or connection-fee detail. */
    public StatementLine(UUID journalId, JournalType kind, Instant occurredAt, String orderId, String description,
                         Money amount, String labelId, String shipmentId, String trackingNumber, String carrier) {
        this(journalId, kind, occurredAt, orderId, description, amount, labelId, shipmentId, trackingNumber, carrier,
                null, null);
    }
}
