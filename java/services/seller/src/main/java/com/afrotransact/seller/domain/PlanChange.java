package com.afrotransact.seller.domain;

/**
 * How a requested plan or cadence change is applied: upgrades on the same cadence prorate immediately; downgrades
 * and cadence changes wait for the next renewal.
 */
public enum PlanChange {
    NO_CHANGE,
    IMMEDIATE_UPGRADE,
    AT_RENEWAL;

    public static PlanChange decide(Plan current, Cadence currentCadence, Plan target, Cadence targetCadence) {
        if (current.code().equals(target.code()) && currentCadence == targetCadence) {
            return NO_CHANGE;
        }
        if (currentCadence == targetCadence && target.monthlyCents() > current.monthlyCents()) {
            return IMMEDIATE_UPGRADE;
        }
        return AT_RENEWAL;
    }
}
