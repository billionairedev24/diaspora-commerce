package com.afrotransact.taxreturns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.Refund;

/** Refunds (S115): partial refunds without units, listing and retries. RMA refunds run inside the return flow. */
public interface RefundService {

    /**
     * Partial refund of amounts of an order's lines with their proportional tax (refunds:issue: staff, or the
     * seller of every line). The same Idempotency-Key replays the same refund.
     */
    Refund adjust(UUID orderId, List<AdjustmentLine> lines, String reason, String idempotencyKey);

    Refund get(UUID id);

    List<Refund> forOrder(UUID orderId);

    List<Refund> forRma(UUID rmaId);

    /** Sends a pending or failed refund to payments again (same Idempotency-Key). */
    Refund retry(UUID id);
}
