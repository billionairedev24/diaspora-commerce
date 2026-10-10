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
 */
public record ReturnsSettings(ReturnAddress returnCentre, String returnCentreNode, double labelWeightLb,
                              Duration refundRetryDelay) {
}
