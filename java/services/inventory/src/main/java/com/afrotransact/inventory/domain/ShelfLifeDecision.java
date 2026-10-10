package com.afrotransact.inventory.domain;

/**
 * Result of a shelf-life check at receipt.
 *
 * @param remainingDays days from receipt to expiry (-1 when the lot does not expire)
 * @param requiredDays  minimum remaining life to be sellable
 */
public record ShelfLifeDecision(Outcome outcome, long remainingDays, long requiredDays, String reason) {

    /** What happens to the lot. */
    public enum Outcome {
        ACCEPT,
        QUARANTINE,
        REJECT
    }
}
