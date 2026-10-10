package com.afrotransact.trustops.domain;

/** Why a seller is suspended. */
public enum SuspensionReason {
    POOR_PERFORMANCE,
    PROHIBITED_ITEMS,
    COUNTERFEIT,
    FRAUD_RISK,
    POLICY_VIOLATION,
    COMPLIANCE,
    OTHER
}
