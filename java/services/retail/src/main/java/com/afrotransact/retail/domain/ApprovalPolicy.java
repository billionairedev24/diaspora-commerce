package com.afrotransact.retail.domain;

/** Decides the approval tier of a purchase order from its value in the base currency. */
public interface ApprovalPolicy {

    ApprovalTier tierFor(long totalBaseMinor);
}
