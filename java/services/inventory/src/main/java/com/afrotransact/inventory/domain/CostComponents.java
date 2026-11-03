package com.afrotransact.inventory.domain;

import java.util.Map;

/**
 * Unit landed cost split into what it is made of, all in the lot's cost currency and in minor units per unit.
 *
 * @param goodsMinor   what the goods cost (FOB)
 * @param freightMinor freight and insurance
 * @param dutyMinor    customs duty and taxes
 * @param otherMinor   brokerage, handling and anything else
 */
public record CostComponents(long goodsMinor, long freightMinor, long dutyMinor, long otherMinor) {

    public CostComponents {
        if (goodsMinor < 0 || freightMinor < 0 || dutyMinor < 0 || otherMinor < 0) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Cost components cannot be negative",
                    Map.of("costComponents", "Must be ≥ 0"));
        }
    }

    /** No breakdown known: the whole cost is the goods. */
    public static CostComponents ofTotal(long totalMinor) {
        return new CostComponents(totalMinor, 0, 0, 0);
    }

    public long totalMinor() {
        return Math.addExact(Math.addExact(goodsMinor, freightMinor), Math.addExact(dutyMinor, otherMinor));
    }
}
