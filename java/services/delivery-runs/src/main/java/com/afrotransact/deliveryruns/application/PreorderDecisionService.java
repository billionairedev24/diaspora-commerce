package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.RefundStatus;
import com.afrotransact.deliveryruns.domain.WindowDecision;

/**
 * Staff decisions about a pre-order window (ADR 0005 §4): the minimum order count, what to do when the close date
 * passes below it, closing early once it is met, and cancelling a window. Every decision needs a reason and is logged
 * (who, when, why) and published. Reads need {@code delivery-runs:read}; decisions {@code preorders:manage}. Writes
 * take the version the caller saw ({@code If-Match}; null = any).
 */
public interface PreorderDecisionService {

    /** What staff can choose for a window waiting for a decision. */
    enum Choice {
        /** Cancel the window: every order is refunded in full and the stock released. */
        CANCEL,
        /** Go ahead below the minimum: the window closes and hands its orders to a run. */
        PROCEED
    }

    /**
     * Decides a window in {@code AWAITING_DECISION}. CANCEL refunds every order (see {@link #cancel}); PROCEED lets the
     * scheduler close the window and hand its paid orders to a run on its next tick. Any other status is 409
     * {@code delivery-runs.invalid-transition}; a blank reason is 422 {@code delivery-runs.reason-required}.
     */
    WindowView decide(UUID windowId, Choice choice, String reason, Long expectedVersion);

    /**
     * Cancels a published window that is not delivered (OPEN, upcoming, closed or waiting for a decision): the window
     * becomes {@code CANCELLED}, a run it was handed to is cancelled too (409 once that run is out for delivery), and
     * {@code preorder.window.cancelled} tells orders-service to refund every order in full, delivery fee included, and
     * notifications to tell the customers. Each order shows its refund progress until it settles.
     */
    WindowView cancel(UUID windowId, String reason, Long expectedVersion);

    /**
     * Closes an OPEN window before its close date once its minimum is met (422
     * {@code delivery-runs.preorder-minimum-not-met} otherwise); the scheduler then closes it as usual.
     */
    WindowView closeEarly(UUID windowId, String reason, Long expectedVersion);

    /** The window's decision log, oldest first. */
    List<WindowDecision> log(UUID windowId);

    /**
     * Refund progress of a cancelled window: orders per refund status (PENDING, REFUNDED, VOIDED, FAILED), orders
     * without progress absent.
     */
    Map<RefundStatus, Integer> refunds(UUID windowId);
}
