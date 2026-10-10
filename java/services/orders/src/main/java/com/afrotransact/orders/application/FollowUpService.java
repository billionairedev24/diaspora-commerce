package com.afrotransact.orders.application;

/**
 * Executes the side effects queued by transitions: capture a shipment on pickup (and commit its stock), start the
 * settlement hold when everything is delivered, refund or release a cancelled shipment.
 */
public interface FollowUpService {

    /** Runs due tasks; returns how many completed. */
    int runDue();
}
