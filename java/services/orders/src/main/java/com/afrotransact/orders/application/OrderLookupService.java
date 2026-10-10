package com.afrotransact.orders.application;

/**
 * Service-to-service order lookup ({@code GET /v1/lookup/orders/{idOrNumber}}): SERVICE principals with
 * {@code orders:lookup} (tax-returns-service, trust-ops-service) or staff with {@code orders:read}. Lets them check
 * ownership and derive payment / line ids without asking anyone to type them.
 */
public interface OrderLookupService {

    OrderLookup lookup(String idOrNumber);
}
