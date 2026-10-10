package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A shipping label a seller bought through AfroTransact (carrier-gateway, S256) and is charged for: the carrier's
 * negotiated cost plus AfroTransact's handling markup, debited from the seller's payable. One per label.
 *
 * @param cost           what the carrier bills AfroTransact for the label (credited to 2400)
 * @param handling       handling markup (credited to 4500; zero unless configured in carrier-gateway)
 * @param chargeJournal  journal of the charge (null for a void that arrived first)
 * @param creditJournal  journal of the credit after a void
 * @param sourceEventId  event-id of the carrier.label.purchased (or the first carrier.label.voided) event
 */
public record LabelCharge(UUID id, String labelId, String orgId, String shipmentId, String orderId, String carrier,
                          String service, String trackingNumber, Money cost, Money handling, LabelChargeStatus status,
                          UUID chargeJournal, UUID creditJournal, UUID sourceEventId, Instant purchasedAt,
                          Instant chargedAt, Instant creditedAt, long version) {

    public LabelCharge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(labelId, "labelId");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(handling, "handling");
        Objects.requireNonNull(status, "status");
        cost.requireSameCurrency(handling);
        if (cost.isNegative() || handling.isNegative() || !cost.plus(handling).isPositive()) {
            throw new PaymentsRuleViolation("payments.invalid-label-charge",
                    "A label charge needs a positive amount (cost and handling are never negative)");
        }
    }

    /** A label charged to the seller now. */
    public static LabelCharge charge(UUID id, String labelId, String orgId, String shipmentId, String orderId,
                                     String carrier, String service, String trackingNumber, Money cost,
                                     Money handling, UUID journal, UUID sourceEventId, Instant purchasedAt,
                                     Instant now) {
        return new LabelCharge(id, labelId, orgId, shipmentId, orderId, carrier, service, trackingNumber, cost,
                handling, LabelChargeStatus.CHARGED, journal, null, sourceEventId,
                purchasedAt == null ? now : purchasedAt, now, null, 0);
    }

    /** A label whose void arrived before its purchase: remembered so that the purchase charges nothing. */
    public static LabelCharge voidedFirst(UUID id, String labelId, String orgId, String shipmentId, String orderId,
                                          String carrier, String service, String trackingNumber, Money cost,
                                          Money handling, UUID sourceEventId, Instant purchasedAt, Instant now) {
        return new LabelCharge(id, labelId, orgId, shipmentId, orderId, carrier, service, trackingNumber, cost,
                handling, LabelChargeStatus.VOIDED, null, null, sourceEventId,
                purchasedAt == null ? now : purchasedAt, null, now, 0);
    }

    /** What the seller pays: carrier cost + handling. */
    public Money amount() {
        return cost.plus(handling);
    }

    /** The charge credited back after a void (only from CHARGED). */
    public LabelCharge credited(UUID journal, Instant now) {
        if (status != LabelChargeStatus.CHARGED) {
            throw new PaymentsStateConflict("payments.label-not-charged", "Label " + labelId + " is " + status);
        }
        return new LabelCharge(id, labelId, orgId, shipmentId, orderId, carrier, service, trackingNumber, cost,
                handling, LabelChargeStatus.CREDITED, chargeJournal, journal, sourceEventId, purchasedAt, chargedAt,
                now, version);
    }
}
