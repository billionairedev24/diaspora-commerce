package com.afrotransact.taxreturns.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaStatus;

/** Persistence port for RMAs. Updates use optimistic locking on {@code version}. */
public interface RmaRepository {

    Rma insert(Rma rma, String idempotencyKey);

    Rma update(Rma rma);

    Optional<Rma> findById(UUID id);

    Optional<Rma> findByNumber(String number);

    Optional<Rma> findByIdempotencyKey(String customerId, String idempotencyKey);

    Optional<Rma> findByTracking(String carrier, String trackingNumber);

    List<Rma> search(Filter filter);

    /** Units per order line in open RMAs (not rejected or cancelled). */
    Map<String, Integer> openQuantities(UUID orderId);

    /** Seller-fulfilled requests whose decision deadline passed. */
    List<Rma> awaitingSellerBefore(Instant now, int limit);

    /** Approved drop-off or driver-pickup RMAs without label / handover code yet. */
    List<Rma> approvedWithoutLabel(int limit);

    /** RMAs whose refund is due but could not be prepared yet (refund state PENDING, no refund recorded). */
    List<Rma> awaitingRefund(int limit);

    /** RMAs with inventory postings to retry. */
    List<Rma> withPendingStock(int limit);

    void recordTransition(UUID rmaId, RmaStatus from, RmaStatus to, String actor, String note, Instant at);

    /** Search filter; null fields are not filtered on. */
    record Filter(String customerId, String sellerOrgId, UUID orderId, RmaStatus status, int limit) {
    }
}
