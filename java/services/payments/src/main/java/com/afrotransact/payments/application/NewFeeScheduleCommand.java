package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.CommissionOverride;
import com.afrotransact.payments.domain.RateTable;

/**
 * A new fee-schedule version.
 *
 * @param effectiveFrom   null = now; never in the past
 * @param deliveryPricing AfroTransact Delivery pricing (S255); null = carried over from the latest live version, so
 *                        callers that don't know the section never switch it off; {@code enabled=false} switches it off
 * @param planBps         product commission per plan (plan → category → bps, {@code *} = the plan's other
 *                        categories); null = carried over from the latest live version, empty = no plan rates
 * @param connectionFee   the service connection-fee section; null = carried over from the latest live version
 */
public record NewFeeScheduleCommand(Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                                    int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps,
                                    long cardFixedMinor, long fbaPerUnitMinor, boolean reverseCommissionOnRefund,
                                    String note, DeliveryPricingCommand deliveryPricing,
                                    Map<String, Map<String, Integer>> planBps, RateTable connectionFee) {

    public NewFeeScheduleCommand {
        categoryBps = categoryBps == null ? Map.of() : Map.copyOf(categoryBps);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }

    /** A version that carries plan rates and the connection-fee section over from the latest one. */
    public NewFeeScheduleCommand(Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                                 int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps,
                                 long cardFixedMinor, long fbaPerUnitMinor, boolean reverseCommissionOnRefund,
                                 String note, DeliveryPricingCommand deliveryPricing) {
        this(effectiveFrom, currency, categoryBps, defaultCommissionBps, overrides, cardPctBps, cardFixedMinor,
                fbaPerUnitMinor, reverseCommissionOnRefund, note, deliveryPricing, null, null);
    }

    /** A version that carries the delivery pricing over from the latest one. */
    public NewFeeScheduleCommand(Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                                 int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps,
                                 long cardFixedMinor, long fbaPerUnitMinor, boolean reverseCommissionOnRefund,
                                 String note) {
        this(effectiveFrom, currency, categoryBps, defaultCommissionBps, overrides, cardPctBps, cardFixedMinor,
                fbaPerUnitMinor, reverseCommissionOnRefund, note, null, null, null);
    }
}
