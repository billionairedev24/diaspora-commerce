package com.afrotransact.orders.application;

/**
 * Service-to-service verified-purchase check ({@code GET /v1/lookup/customers/{customerId}/purchases/{atin}}):
 * catalog-service asks, with scope {@code orders:purchase-check}, whether a customer has a delivered order line for a
 * product ATIN before it accepts that customer's review (S324). Answers yes or no plus the delivering order, never
 * its contents.
 */
public interface PurchaseCheckService {

    PurchaseCheck check(String customerId, String productAtin);
}
