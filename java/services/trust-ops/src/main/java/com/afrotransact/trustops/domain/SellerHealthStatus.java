package com.afrotransact.trustops.domain;

/** Design: Health column "Good" / "At risk". UNKNOWN when the seller had no shipments in the window. */
public enum SellerHealthStatus {
    GOOD,
    AT_RISK,
    UNKNOWN
}
