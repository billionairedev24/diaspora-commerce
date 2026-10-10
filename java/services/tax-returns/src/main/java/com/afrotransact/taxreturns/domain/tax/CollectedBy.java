package com.afrotransact.taxreturns.domain.tax;

/** Who collects and remits the sales tax of a line. */
public enum CollectedBy {
    /** AfroTransact collects and remits as marketplace facilitator. */
    MARKETPLACE,
    /** No tax is collected by the marketplace (non-facilitator jurisdiction, no sales tax, or outside the US). */
    NOT_COLLECTED
}
