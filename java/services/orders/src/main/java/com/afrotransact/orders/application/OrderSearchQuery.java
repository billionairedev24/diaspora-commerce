package com.afrotransact.orders.application;

import java.time.Instant;

/** Staff order search; null fields mean "any". Statuses are wire names (placed, in_transit …). */
public record OrderSearchQuery(String number, String customerId, String sellerOrgId, String status,
                               String shipmentStatus, Instant from, Instant to) {
}
