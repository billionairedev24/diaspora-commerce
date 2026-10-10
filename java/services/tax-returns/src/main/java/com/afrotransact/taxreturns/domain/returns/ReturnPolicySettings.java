package com.afrotransact.taxreturns.domain.returns;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Platform return policy.
 *
 * @param defaultWindowDays          return window after delivery when nothing more specific applies
 * @param windowDaysByProductType    window per catalog product type
 * @param nonReturnableProductTypes  product types that can only be reported as a problem (perishables are always)
 * @param lowValueThresholdMinor     refund without return ("keep the item") at or below this line value
 * @param firstScanRefundMaxMinor    refund at first carrier scan for AfroTransact-fulfilled returns up to this value
 * @param sellerApprovalSla          seller-fulfilled requests auto-approve when the seller has not decided in time
 * @param dropOffDays                days to drop the parcel off after the label is issued
 * @param maxRestockingFeeBps        cap on any restocking fee
 * @param maxEvidence                maximum evidence photos per line
 */
public record ReturnPolicySettings(int defaultWindowDays, Map<String, Integer> windowDaysByProductType,
                                   Set<String> nonReturnableProductTypes, long lowValueThresholdMinor,
                                   long firstScanRefundMaxMinor, Duration sellerApprovalSla, int dropOffDays,
                                   int maxRestockingFeeBps, int maxEvidence) {

    public ReturnPolicySettings {
        windowDaysByProductType = Map.copyOf(windowDaysByProductType);
        nonReturnableProductTypes = Set.copyOf(nonReturnableProductTypes);
    }
}
