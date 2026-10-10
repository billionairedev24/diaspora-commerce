package com.afrotransact.payments.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.PayoutRequest;
import com.afrotransact.payments.domain.PayoutStatus;

/** Payout requests. */
public interface PayoutRepository {

    void insert(PayoutRequest request);

    /** Optimistic update; returns the stored row with version + 1. */
    PayoutRequest update(PayoutRequest request);

    Optional<PayoutRequest> find(UUID id);

    Optional<PayoutRequest> lock(UUID id);

    Optional<PayoutRequest> lockByStripeId(String stripePayoutId);

    List<PayoutRequest> list(Optional<PayoutStatus> status, Optional<String> orgId, int limit);

    List<PayoutRequest> requestsOn(String orgId, LocalDate day);

    /** Σ amount per status for the organisation in {@code currency}. */
    Map<PayoutStatus, Long> totals(String orgId, String currency);
}
