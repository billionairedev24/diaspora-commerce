package com.afrotransact.deliveryruns.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a customer pays for delivery and which rule said so. Orders keep it as the snapshot of the order, so later
 * edits of a rule never change an order already placed.
 *
 * @param method        how the order is delivered
 * @param ruleId        the rule applied (null when staff set the fee on the window and no rule exists)
 * @param ruleVersion   its version (0 without a rule)
 * @param currency      ISO 4217
 * @param amountMinor   the fee
 * @param source        where the amount came from
 * @param band          the distance band applied ("0–10 mi"), the threshold that made it free, or null
 * @param distanceMiles the distance the band was chosen by (null when not measured or not distance based)
 */
public record FeeSnapshot(FeeMethod method, UUID ruleId, long ruleVersion, String currency, long amountMinor,
                          FeeSource source, String band, BigDecimal distanceMiles) {

    public FeeSnapshot {
        if (method == null || source == null || amountMinor < 0) {
            throw new IllegalArgumentException("method, source and a non-negative amount are required");
        }
    }

    public Money amount() {
        return new Money(amountMinor, currency);
    }
}
