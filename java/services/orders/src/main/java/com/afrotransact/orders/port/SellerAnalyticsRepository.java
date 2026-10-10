package com.afrotransact.orders.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Read-side aggregates of a seller organisation's shipments for the Seller Center dashboard. */
public interface SellerAnalyticsRepository {

    /**
     * Per local day (orders created in [from, to) grouped in {@code zone}): checkouts that reached payment with the
     * org's items, paid orders, sales (items − discounts of live and delivered shipments) and units.
     */
    List<DayRow> days(String sellerOrgId, Instant from, Instant to, String zone);

    /** Products by revenue in [from, to), best first. */
    List<ProductRow> topProducts(String sellerOrgId, Instant from, Instant to, int limit);

    /** Paid orders per fulfilment type in [from, to). */
    List<FulfilmentRow> byFulfilment(String sellerOrgId, Instant from, Instant to);

    /** Shipments waiting for the seller now (placed, confirmed or packed). */
    int toShip(String sellerOrgId);

    /** Currency of the org's latest order (USD when none). */
    String currency(String sellerOrgId);

    /** One day. */
    record DayRow(LocalDate date, int checkouts, int orders, long salesMinor, int units) {
    }

    /** One product. */
    record ProductRow(String productAtin, String title, long revenueMinor, int units) {
    }

    /** One fulfilment type ({@code FulfilmentType} name). */
    record FulfilmentRow(String fulfilmentType, int orders) {
    }
}
