package com.afrotransact.deliveryruns.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Money payments-service reports for orders that hold a run slot (S265): the platform's commission on the goods
 * ({@code payments.captured}) and the refunds it issued ({@code payments.refunded}). They are kept on the order's
 * slot so a run's P&amp;L and its "Platform fees on goods" line come from real data. Both are idempotent per capture /
 * refund id; orders without a run slot are ignored.
 */
public interface RunMoneyService {

    /**
     * A capture settled; the commission on the goods adds to the order's.
     *
     * @param captureId       the capture (idempotency)
     * @param commissionMinor the platform's commission across the capture's seller recipients
     */
    void onCaptured(UUID captureId, String orderId, long commissionMinor, Instant at);

    /**
     * A refund payments issued (status SUCCEEDED only; pending and failed ones are not money handed back).
     *
     * @param refundId      the refund (idempotency)
     * @param amountMinor   everything handed back
     * @param shippingMinor the part of it that was delivery fee
     */
    void onRefunded(UUID refundId, String orderId, long amountMinor, long shippingMinor, Instant at);
}
