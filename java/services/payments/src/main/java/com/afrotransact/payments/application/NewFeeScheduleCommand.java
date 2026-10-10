package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.CommissionOverride;

/**
 * A new fee-schedule version.
 *
 * @param effectiveFrom null = now; never in the past
 */
public record NewFeeScheduleCommand(Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                                    int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps,
                                    long cardFixedMinor, long fbaPerUnitMinor, boolean reverseCommissionOnRefund,
                                    String note) {

    public NewFeeScheduleCommand {
        categoryBps = categoryBps == null ? Map.of() : Map.copyOf(categoryBps);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }
}
