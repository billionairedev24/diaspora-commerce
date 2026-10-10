package com.afrotransact.trustops.domain;

import java.time.Instant;

/** Turns metrics into a health status against the marketplace targets. */
public interface SellerHealthPolicy {

    SellerHealthReport assess(SellerHealthMetrics metrics, Instant now);
}
