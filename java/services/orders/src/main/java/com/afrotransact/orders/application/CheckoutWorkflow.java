package com.afrotransact.orders.application;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Checkout saga (architecture §5): validate cart → price snapshot + tax → reserve inventory (all or nothing,
 * idempotency key = order id) → create PaymentIntent (manual capture) → persist order + shipments
 * (PENDING_PAYMENT) → wait for the payment authorisation (signal, 15 min) → confirm reservation, place the order,
 * clear the cart. Any failure runs the compensations: release reservation, cancel intent, void order.
 */
@WorkflowInterface
public interface CheckoutWorkflow {

    /** Task queue of the checkout worker. */
    String TASK_QUEUE = "orders-checkout";

    @WorkflowMethod
    CheckoutProgress checkout(CheckoutInput input);

    /** payments.intent.succeeded, or the client confirmed and payments reports AUTHORIZED. */
    @SignalMethod
    void paymentAuthorized();

    /** payments.intent.failed / canceled. */
    @SignalMethod
    void paymentFailed(String reason);

    @QueryMethod
    CheckoutProgress progress();
}
