package com.afrotransact.orders.application;

/** Starts and signals checkout sagas (Temporal client). */
public interface CheckoutWorkflows {

    /** Starts the saga; a second start of the same workflow id is a no-op. */
    void start(CheckoutInput input);

    /** True when a workflow with this id was ever started (running or closed). */
    boolean exists(String workflowId);

    /** Delivers the payment outcome; ignored when the saga already finished. */
    void signalPayment(String workflowId, boolean authorized, String reason);
}
