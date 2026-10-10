package com.afrotransact.orders.application;

import java.time.LocalDate;

/** Sales, orders and checkout conversion by day for a seller organisation (Seller Center dashboard). */
public interface SellerAnalyticsService {

    /** Longest range served, in days. */
    int MAX_DAYS = 366;

    /**
     * Figures for [from, to] (inclusive local dates; default the last 30 days). Sellers get their token's org;
     * staff with {@code orders:read} may name {@code sellerOrgId}.
     */
    SellerAnalytics analytics(String sellerOrgId, LocalDate from, LocalDate to);
}
