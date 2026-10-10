package com.afrotransact.taxreturns.domain.returns;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Return merchandise authorisation: the lines of one shipment coming back (or refunded without return).
 * Immutable; every transition returns a new instance and is checked against {@link RmaStatus#next()}.
 *
 * @param id                 RMA id
 * @param number             customer-facing number ({@code RMA-XXXXXXXX})
 * @param orderId            order id
 * @param orderNumber        order number
 * @param shipmentId         shipment the lines belong to
 * @param customerId         identity subject of the shopper
 * @param sellerOrgId        seller organisation of the shipment
 * @param fulfilment         fulfilment type of the shipment
 * @param delivery           how the shipment was delivered
 * @param method             how the items come back
 * @param status             lifecycle status
 * @param lines              returned lines
 * @param currency           ISO currency
 * @param estimateMinor      refund estimate shown at request time
 * @param autoApproved       approved without a human decision
 * @param approvalDueAt      seller decision deadline (null when not waiting for a seller)
 * @param refundAtFirstScan  refund at the first carrier / driver scan
 * @param refundState        refund progress
 * @param label              carrier return label (drop-off)
 * @param qrPayload          what the app renders as the drop-off / handover QR code
 * @param handoverCode       6-digit code the customer gives the driver (driver pickup)
 * @param returnFrom         where the parcel ships from (drop-off)
 * @param dropOffBy          drop the parcel off before this time
 * @param decisionNote       seller / staff note on approval, rejection or inspection
 * @param createdAt          request time
 * @param updatedAt          last change
 * @param version            optimistic-lock version
 */
public record Rma(UUID id, String number, UUID orderId, String orderNumber, UUID shipmentId, String customerId,
                  String sellerOrgId, FulfilmentType fulfilment, DeliveryMethod delivery, ReturnMethod method,
                  RmaStatus status, List<RmaLine> lines, String currency, long estimateMinor, boolean autoApproved,
                  Instant approvalDueAt, boolean refundAtFirstScan, RefundState refundState, ReturnLabel label,
                  String qrPayload, String handoverCode, ReturnAddress returnFrom, Instant dropOffBy,
                  String decisionNote, Instant createdAt, Instant updatedAt, long version) {

    public Rma {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(method, "method");
        lines = List.copyOf(lines);
        refundState = refundState == null ? RefundState.NONE : refundState;
    }

    /** A new request awaiting approval. */
    public static Rma requested(UUID id, String number, UUID orderId, String orderNumber, UUID shipmentId,
                                String customerId, String sellerOrgId, FulfilmentType fulfilment,
                                DeliveryMethod delivery, ReturnMethod method, List<RmaLine> lines, String currency,
                                long estimateMinor, Instant approvalDueAt, boolean refundAtFirstScan,
                                ReturnAddress returnFrom, Instant now) {
        return new Rma(id, number, orderId, orderNumber, shipmentId, customerId, sellerOrgId, fulfilment, delivery,
                method, RmaStatus.REQUESTED, lines, currency, estimateMinor, false, approvalDueAt, refundAtFirstScan,
                RefundState.NONE, null, null, null, returnFrom, null, null, now, now, 0L);
    }

    public Rma approve(boolean auto, String note, Instant at) {
        RmaStatus to = auto ? RmaStatus.AUTO_APPROVED : RmaStatus.APPROVED;
        check(to);
        return copy(to, lines, auto, null, refundState, label, qrPayload, handoverCode, dropOffBy,
                note == null ? decisionNote : note, at);
    }

    public Rma reject(String note, Instant at) {
        check(RmaStatus.REJECTED);
        return copy(RmaStatus.REJECTED, lines, autoApproved, null, refundState, label, qrPayload, handoverCode,
                dropOffBy, note, at);
    }

    public Rma cancel(Instant at) {
        check(RmaStatus.CANCELLED);
        return copy(RmaStatus.CANCELLED, lines, autoApproved, null, refundState, label, qrPayload, handoverCode,
                dropOffBy, decisionNote, at);
    }

    /** Drop-off: the carrier label is bought. */
    public Rma labelIssued(ReturnLabel issued, String qr, Instant dropOffDeadline, Instant at) {
        if (method != ReturnMethod.DROP_OFF) {
            throw new IllegalStateException("Only drop-off returns get a carrier label");
        }
        check(RmaStatus.LABEL_ISSUED);
        return copy(RmaStatus.LABEL_ISSUED, lines, autoApproved, approvalDueAt, refundState, issued, qr, handoverCode,
                dropOffDeadline, decisionNote, at);
    }

    /** Driver pickup: a handover code replaces the label. */
    public Rma pickupArranged(String code, String qr, Instant pickupBy, Instant at) {
        if (method != ReturnMethod.DRIVER_PICKUP) {
            throw new IllegalStateException("Only driver-pickup returns get a handover code");
        }
        check(RmaStatus.LABEL_ISSUED);
        return copy(RmaStatus.LABEL_ISSUED, lines, autoApproved, approvalDueAt, refundState, label, qr, code,
                pickupBy, decisionNote, at);
    }

    public Rma inTransit(Instant at) {
        check(RmaStatus.IN_TRANSIT);
        return copy(RmaStatus.IN_TRANSIT, lines, autoApproved, approvalDueAt, refundState, label, qrPayload,
                handoverCode, dropOffBy, decisionNote, at);
    }

    public Rma received(Instant at) {
        check(RmaStatus.RECEIVED);
        return copy(RmaStatus.RECEIVED, lines, autoApproved, approvalDueAt, refundState, label, qrPayload,
                handoverCode, dropOffBy, decisionNote, at);
    }

    public Rma inspected(List<RmaLine> inspectedLines, String note, Instant at) {
        check(RmaStatus.INSPECTED);
        return copy(RmaStatus.INSPECTED, inspectedLines, autoApproved, approvalDueAt, refundState, label, qrPayload,
                handoverCode, dropOffBy, note == null ? decisionNote : note, at);
    }

    /** Closes the RMA as refunded (after inspection, or directly for refunds without return). */
    public Rma refunded(Instant at) {
        check(RmaStatus.REFUNDED);
        return copy(RmaStatus.REFUNDED, lines, autoApproved, approvalDueAt, refundState, label, qrPayload,
                handoverCode, dropOffBy, decisionNote, at);
    }

    public Rma withRefundState(RefundState state, Instant at) {
        return copy(status, lines, autoApproved, approvalDueAt, state, label, qrPayload, handoverCode, dropOffBy,
                decisionNote, at);
    }

    public Rma withLines(List<RmaLine> updated, Instant at) {
        return copy(status, updated, autoApproved, approvalDueAt, refundState, label, qrPayload, handoverCode,
                dropOffBy, decisionNote, at);
    }

    /** Lines that passed inspection (all lines before inspection or when nothing is sent back). */
    public List<RmaLine> refundableLines() {
        return lines.stream().filter(l -> l.outcome() == null || l.outcome() == InspectionOutcome.PASS).toList();
    }

    public int quantityOf(String lineId) {
        return lines.stream().filter(l -> l.lineId().equals(lineId)).mapToInt(RmaLine::quantity).sum();
    }

    private void check(RmaStatus to) {
        if (!status.canMoveTo(to)) {
            throw new IllegalStateException("Return " + number + " can't go from " + status.name().toLowerCase(Locale.ROOT)
                    + " to " + to.name().toLowerCase(Locale.ROOT));
        }
    }

    private Rma copy(RmaStatus newStatus, List<RmaLine> newLines, boolean auto, Instant due, RefundState refund,
                     ReturnLabel newLabel, String qr, String code, Instant dropBy, String note, Instant at) {
        return new Rma(id, number, orderId, orderNumber, shipmentId, customerId, sellerOrgId, fulfilment, delivery,
                method, newStatus, newLines, currency, estimateMinor, auto, due, refundAtFirstScan, refund, newLabel,
                qr, code, returnFrom, dropBy, note, createdAt, at, version);
    }
}
