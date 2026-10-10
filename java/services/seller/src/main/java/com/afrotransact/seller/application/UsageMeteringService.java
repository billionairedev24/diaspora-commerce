package com.afrotransact.seller.application;

import java.time.LocalDate;
import java.util.List;

/**
 * Metered billing (S206). A run reads FBA storage (inventory), ads spend (content) and AI credits (ai-gateway) per
 * organisation and UTC day for a period, stores each day's quantity (overwriting earlier reads, so a re-run is exact)
 * and sends Stripe Billing meter events for the difference between the quantity and what Stripe already has. Runs
 * are idempotent and re-runnable per period; one runs at a time across replicas. AfroTransact Retail is never
 * metered: its samples are dropped before anything is stored or sent to Stripe.
 */
public interface UsageMeteringService {

    /** Longest period one run may cover. */
    int MAX_DAYS = 93;

    /** Staff with {@code billing:usage}: (re-)meters [from, to] (to at most today, UTC). */
    UsageRunView run(LocalDate from, LocalDate to);

    /** Daily job: the two previous UTC days. */
    UsageRunView runScheduled();

    /** Recent runs (staff with {@code billing:usage}). */
    List<UsageRunView> runs();

    /**
     * Usage statement of an organisation: owner / finance members, staff with {@code seller:billing} or
     * {@code billing:usage}. Defaults to the current subscription period (or the month to date without one).
     */
    UsageStatement statement(String orgId, LocalDate from, LocalDate to);
}
