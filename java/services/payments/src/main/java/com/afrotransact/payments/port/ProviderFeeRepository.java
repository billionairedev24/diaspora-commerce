package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.ProviderFee;

/** Fees owed by provider organisations (late provider cancellations), one per booking. */
public interface ProviderFeeRepository {

    /** false when the booking already has a fee (business-key de-duplication). */
    boolean insert(ProviderFee fee);

    Optional<ProviderFee> findByBooking(String bookingId);

    /** SELECT … FOR UPDATE. */
    Optional<ProviderFee> lock(UUID id);

    /** The oldest OUTSTANDING fee due at {@code now}, locked with SKIP LOCKED (safe on several replicas). */
    Optional<ProviderFee> lockNextDue(Instant now);

    /** Optimistic: the version must match; returns the fee with version + 1. */
    ProviderFee update(ProviderFee fee);

    /** Every fee (outstanding or collected) of the organisation in the currency: deducted from its payout balance. */
    Money totalOf(String orgId, String currency);

    List<ProviderFee> ofOrg(String orgId, int limit);
}
