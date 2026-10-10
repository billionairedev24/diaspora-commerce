package com.afrotransact.payments.application;

import java.util.UUID;

/**
 * Off-session charges on saved cards for balances due beyond the card-authorisation window (~7 days): long-dated
 * bookings capture the deposit, release the rest of the hold and, near the appointment, authorise the balance again
 * off-session. The result is a new manual-capture payment of the same order (captured, refunded and settled through
 * the usual endpoints). When the bank requires authentication (SCA) the payment is REQUIRES_ACTION and
 * {@code payments.intent.action_required} asks the customer to confirm it on-session.
 */
public interface OffSessionChargeService {

    /** Creates and confirms the off-session PaymentIntent. Idempotent per Idempotency-Key. */
    PaymentResult charge(UUID paymentId, OffSessionCommand command, String idempotencyKey);
}
