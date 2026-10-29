package com.afrotransact.orders.port;

import java.util.Optional;

import com.afrotransact.orders.domain.FeeRuleSnapshot;

/**
 * delivery-runs {@code POST /v1/service/delivery-fees/carrier} with the orders-service token
 * ({@code delivery-runs:reserve}): the staff-configured carrier rate for an order shipped beyond coverage (ADR 0005
 * §3). Window and run fees come with the pre-order eligibility answer instead.
 */
public interface DeliveryFeeRules {

    /**
     * The carrier fee under the staff rule, with the rule to snapshot. Empty when staff configured no carrier rule
     * for the currency, or delivery-runs cannot answer (the checkout then uses its flat carrier rates).
     *
     * @param quoteMinor the carrier-gateway quote for the package, or null when there is none
     * @param itemsMinor the package's goods subtotal (free-shipping threshold)
     */
    Optional<FeeRuleSnapshot> carrier(String currency, Long quoteMinor, long itemsMinor);
}
