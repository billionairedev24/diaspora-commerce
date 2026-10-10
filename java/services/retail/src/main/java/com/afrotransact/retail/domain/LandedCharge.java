package com.afrotransact.retail.domain;

import java.util.Objects;

/** One landed-cost charge (freight invoice, duty on the CBP 7501, brokerage …) in base-currency minor units. */
public record LandedCharge(ChargeType type, long amountMinor, String currency, AllocationBasis basis,
                           String reference) {

    public LandedCharge {
        Objects.requireNonNull(type, "type");
        if (amountMinor < 0) {
            throw new RetailRuleViolation("retail.charge-invalid", type + " amount cannot be negative");
        }
        currency = Currencies.require(currency);
        basis = basis == null ? type.defaultBasis() : basis;
    }
}
