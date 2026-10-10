package com.afrotransact.taxreturns.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.Refund;
import com.afrotransact.taxreturns.domain.returns.RefundedTotals;

/** Persistence port for refunds. */
public interface RefundRepository {

    /** Inserts; when the request key exists, returns the stored refund instead. */
    Refund insertOrGet(Refund refund);

    Refund update(Refund refund);

    Optional<Refund> findById(UUID id);

    Optional<Refund> findByRequestKey(String requestKey);

    List<Refund> findByOrder(UUID orderId);

    List<Refund> findByRma(UUID rmaId);

    /** Pending refunds created before {@code before}. */
    List<Refund> pendingBefore(Instant before, int limit);

    /** Per order line: what pending and issued refunds already took. */
    Map<String, RefundedTotals> refundedTotals(UUID orderId);
}
