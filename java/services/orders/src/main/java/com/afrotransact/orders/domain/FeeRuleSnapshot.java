package com.afrotransact.orders.domain;

import java.math.BigDecimal;

/**
 * The delivery fee rule a shipment was charged by (ADR 0005 §3), kept on the shipment at checkout. Staff edit rules
 * later; an order placed earlier keeps the method, rule version, band and amount it was charged. Never re-priced.
 *
 * @param method        AFROTRANSACT_DELIVERY, CARRIER or PICKUP
 * @param ruleId        delivery-runs' rule (null when staff set the fee on the window and no rule existed)
 * @param ruleVersion   the version applied (0 without a rule)
 * @param currency      ISO 4217
 * @param amountMinor   the fee charged
 * @param source        RULE, WINDOW_OVERRIDE, CARRIER_QUOTE or CARRIER_FALLBACK
 * @param band          the distance band applied ("10–40 mi"), the free-over threshold, or null
 * @param distanceMiles the distance the band was chosen by, or null
 */
public record FeeRuleSnapshot(String method, String ruleId, long ruleVersion, String currency, long amountMinor,
                              String source, String band, BigDecimal distanceMiles) {

    public FeeRuleSnapshot {
        if (method == null || method.isBlank() || source == null || source.isBlank()) {
            throw new IllegalArgumentException("method and source are required");
        }
        if (amountMinor < 0) {
            throw new OrdersRuleViolation("orders.invalid-amount", "Delivery fees cannot be negative");
        }
        currency = currency == null || currency.isBlank() ? "USD" : currency;
    }
}
