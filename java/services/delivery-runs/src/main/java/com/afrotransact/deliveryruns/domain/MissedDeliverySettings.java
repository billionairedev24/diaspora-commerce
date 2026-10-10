package com.afrotransact.deliveryruns.domain;

/**
 * Choices offered to the customer when a delivery date is missed.
 *
 * @param offerRefund  full refund
 * @param offerNewDate wait for the new date
 */
public record MissedDeliverySettings(boolean offerRefund, boolean offerNewDate) {

    public static final MissedDeliverySettings DEFAULT = new MissedDeliverySettings(true, true);
}
