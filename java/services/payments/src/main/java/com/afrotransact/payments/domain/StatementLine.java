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
 */
public record StatementLine(UUID journalId, JournalType kind, Instant occurredAt, String orderId, String description,
                            Money amount, String labelId, String shipmentId, String trackingNumber, String carrier) {
}
