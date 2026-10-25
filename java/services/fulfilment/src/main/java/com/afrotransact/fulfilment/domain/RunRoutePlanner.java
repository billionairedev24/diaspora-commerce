package com.afrotransact.fulfilment.domain;

import java.util.List;

/**
 * Splits a run's drops into routes (S264): one route per run, or several when the run's packages do not fit one van.
 * Drops are visited nearest the hub first; an order's drop is never split across routes.
 */
public interface RunRoutePlanner {

    /**
     * @param drops               the run's drops, in any order
     * @param maxPackagesPerRoute the most bags one route carries (at least 1)
     * @return the routes, each with its drops in visiting order (nearest first, unmeasured ones last by order number);
     *         empty when there are no drops. Routes are balanced: a run of 50 bags with room for 40 makes two of 25.
     */
    List<List<RunDrop>> plan(List<RunDrop> drops, int maxPackagesPerRoute);
}
