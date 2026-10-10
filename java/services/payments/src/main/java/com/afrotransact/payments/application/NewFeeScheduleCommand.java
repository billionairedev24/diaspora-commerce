package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.CommissionOverride;

/**
 * A new fee-schedule version.
 *
 * @param effectiveFrom   null = now; never in the past
 * @param deliveryPricing AfroTransact Delivery pricing (S255); null = carried over from the latest live version, so
 *                        callers that don't know the section never switch it off; {@code enabled=false} switches it off
 */
public record NewFeeScheduleCommand(Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                                    int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps,
                                    long cardFixedMinor, long fbaPerUnitMinor, boolean reverseCommissionOnRefund,
                                    String note, DeliveryPricingCommand deliveryPricing) {

    public NewFeeScheduleCommand {
        categoryBps = categoryBps == null ? Map.of() : Map.copyOf(categoryBps);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }

    /** A version that carries the delivery pricing over from the latest one. */
    public NewFeeScheduleCommand(Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                                 int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps,
                                 long cardFixedMinor, long fbaPerUnitMinor, boolean reverseCommissionOnRefund,
                                 String note) {
        this(effectiveFrom, currency, categoryBps, defaultCommissionBps, overrides, cardPctBps, cardFixedMinor,
                fbaPerUnitMinor, reverseCommissionOnRefund, note, null);
    }
}
