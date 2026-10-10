package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.Optional;

import com.afrotransact.payments.domain.PayoutHold;

/** Compliance payout holds per organisation (mirrored from seller-service events). */
public interface PayoutHoldRepository {

    /**
     * Stores the hold unless a newer decision ({@code changedAt}) is already stored.
     *
     * @return true when the stored state changed
     */
    boolean apply(PayoutHold hold, Instant now);

    Optional<PayoutHold> find(String orgId);
}
