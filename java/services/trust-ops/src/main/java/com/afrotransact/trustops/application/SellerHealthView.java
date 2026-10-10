package com.afrotransact.trustops.application;

import com.afrotransact.trustops.domain.SellerEnforcement;
import com.afrotransact.trustops.domain.SellerHealthReport;

/** Current metrics of a seller, the last stored snapshot's push state and its standing. */
public record SellerHealthView(SellerHealthReport current, boolean lastPushedToOffers, SellerEnforcement standing) {
}
