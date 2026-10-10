package com.afrotransact.retail.domain;

/** Orders strictly above the threshold (default $10,000.00) need Finance approval. */
public class DefaultApprovalPolicy implements ApprovalPolicy {

    private final long financeThresholdMinor;

    public DefaultApprovalPolicy(long financeThresholdMinor) {
        if (financeThresholdMinor < 0) {
            throw new IllegalArgumentException("Threshold cannot be negative");
        }
        this.financeThresholdMinor = financeThresholdMinor;
    }

    @Override
    public ApprovalTier tierFor(long totalBaseMinor) {
        return totalBaseMinor > financeThresholdMinor ? ApprovalTier.FINANCE : ApprovalTier.STANDARD;
    }
}
