package com.afrotransact.payments.application;

import java.util.UUID;

import com.afrotransact.payments.domain.PaymentSplit;

/**
 * The "Payment split" staff view of an order.
 *
 * @param feeSource ACTUAL (every capture's Stripe fee known), ESTIMATED (nothing captured yet: the schedule's card
 *                  fee) or MIXED
 */
public record OrderSplit(String orderId, UUID paymentId, int feeScheduleVersion, String feeSource,
                         PaymentSplit split) {
}
