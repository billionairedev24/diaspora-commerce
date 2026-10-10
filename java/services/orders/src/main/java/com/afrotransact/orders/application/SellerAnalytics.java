package com.afrotransact.orders.application;

import java.time.LocalDate;
import java.util.List;

/**
 * Seller dashboard figures for a date range (business time zone). Conversion is checkout conversion: of the
 * checkouts that reached payment with the seller's items, the share that was paid.
 */
public record SellerAnalytics(String sellerOrgId, String currency, LocalDate from, LocalDate to, List<Day> days,
                              List<Product> topProducts, List<FulfilmentCount> byFulfilment, int toShip,
                              Totals totals) {

    public SellerAnalytics {
        days = List.copyOf(days);
        topProducts = List.copyOf(topProducts);
        byFulfilment = List.copyOf(byFulfilment);
    }

    /** One calendar day (every day of the range is present). */
    public record Day(LocalDate date, long salesMinor, int units, int orders, int checkouts, double conversionRate) {
    }

    /** A product by revenue. */
    public record Product(String atin, String title, long revenueMinor, int units) {
    }

    /** Paid orders by fulfilment: AFROTRANSACT (FBA and Retail 1P) or SELLER. */
    public record FulfilmentCount(String fulfilment, int orders) {
    }

    /** Range totals. */
    public record Totals(long salesMinor, int units, int orders, int checkouts, double conversionRate) {
    }
}
