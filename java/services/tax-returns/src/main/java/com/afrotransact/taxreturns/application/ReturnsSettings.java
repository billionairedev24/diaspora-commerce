package com.afrotransact.taxreturns.application;

import java.time.Duration;

import com.afrotransact.taxreturns.domain.returns.ReturnAddress;

/**
 * Settings the return use cases need beyond the domain policy.
 *
 * @param returnCentre     destination of AfroTransact-fulfilled returns
 * @param returnCentreNode carrier-gateway origin node of the return centre
 * @param labelWeightLb    parcel weight used for return labels
 * @param refundRetryDelay pending refunds older than this are retried by the job
 * @param pickupReadyDays  driver pickups are scheduled from this many days after approval
 * @param returnCentreInventoryNode inventory / fulfilment node of the return centre (receiving warehouse of
 *                         AfroTransact-fulfilled returns, depot of their driver pickups); null when not configured
 */
public record ReturnsSettings(ReturnAddress returnCentre, String returnCentreNode, double labelWeightLb,
                              Duration refundRetryDelay, int pickupReadyDays, String returnCentreInventoryNode) {

    public ReturnsSettings {
        returnCentreInventoryNode = returnCentreInventoryNode == null || returnCentreInventoryNode.isBlank() ? null
                : returnCentreInventoryNode.trim();
    }

    public ReturnsSettings(ReturnAddress returnCentre, String returnCentreNode, double labelWeightLb,
                           Duration refundRetryDelay) {
        this(returnCentre, returnCentreNode, labelWeightLb, refundRetryDelay, 1, null);
    }
}
