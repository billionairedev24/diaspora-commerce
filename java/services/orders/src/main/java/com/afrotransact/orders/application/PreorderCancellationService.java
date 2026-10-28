package com.afrotransact.orders.application;

import java.util.Collection;

/**
 * Cancels pre-order packages because their window or run was cancelled (ADR 0005 §1): every PREORDER shipment is
 * cancelled through the same state machine and follow-up a customer's own cancellation uses, so the captured payment is
 * refunded in full (delivery fee included) or, when nothing was captured, the authorisation is released, and stock held
 * for the lines is released. Idempotent: a shipment already cancelled is left alone, so a redelivered event, a retry or
 * the window event followed by the run event does no harm. Progress is published as
 * {@code orders.preorder.refund_settled} / {@code orders.preorder.refund_failed} by the follow-up.
 */
public interface PreorderCancellationService {

    /** History actor of cancellations made on behalf of a cancelled window or run. */
    String ACTOR = "system:preorder-cancelled";

    /** Cancels every placed PREORDER shipment of the window ({@code preorder.window.cancelled}). */
    Result cancelWindow(String windowId, String reason);

    /** Cancels the PREORDER shipments of the given orders ({@code delivery-runs.run.cancelled}). */
    Result cancelOrders(Collection<String> orderIds, String reason);

    /**
     * What a call did, in shipments.
     *
     * @param cancelled        cancelled now (the refund follows asynchronously)
     * @param alreadyCancelled were cancelled before
     * @param skipped          never placed or paid (nothing to refund), or not pre-order packages
     * @param failed           could not be cancelled (already on its way); {@code refund_failed} was published
     */
    record Result(int cancelled, int alreadyCancelled, int skipped, int failed) {

        public Result plus(Result other) {
            return new Result(cancelled + other.cancelled, alreadyCancelled + other.alreadyCancelled,
                    skipped + other.skipped, failed + other.failed);
        }
    }
}
