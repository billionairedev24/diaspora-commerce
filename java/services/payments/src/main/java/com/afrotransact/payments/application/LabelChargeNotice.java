package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A label bought or voided through carrier-gateway ({@code carrier.label.purchased} / {@code carrier.label.voided} on
 * {@code at.carrier-shipment.events}).
 *
 * @param eventId     the event-id header; the inbox de-duplicates on it
 * @param kind        outbound or return
 * @param fulfilment  SELLER_SHIPPED, FBA or AFROTRANSACT_DELIVERY
 * @param billTo      SELLER or AFROTRANSACT (decided by carrier-gateway)
 * @param costMinor   the carrier's negotiated charge
 * @param handlingMinor handling markup (carrier-gateway billing config)
 */
public record LabelChargeNotice(UUID eventId, String labelId, String orgId, String shipmentId, String orderId,
                                String carrier, String service, String trackingNumber, String kind,
                                String fulfilment, String billTo, long costMinor, long handlingMinor,
                                String currency, Instant purchasedAt) {
}
