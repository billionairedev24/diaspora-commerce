package com.afrotransact.orders.application;

/** The quote jobs: escalate late requests, expire unused quotes. Safe to run on every replica at once. */
public interface DeliveryQuoteSweepService {

    /** Escalates requests that are due; returns how many moved up. */
    int escalate();

    /** Expires ready quotes past their validity; returns how many. */
    int expire();
}
