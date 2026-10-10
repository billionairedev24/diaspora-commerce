package com.afrotransact.retail.domain;

/** Landed-cost components on top of FOB (design: freight, insurance, fees, broker, drayage, receiving, duty). */
public enum ChargeType {
    FREIGHT,
    INSURANCE,
    DUTY,
    BROKERAGE,
    DRAYAGE,
    FEES,
    RECEIVING;

    /** Default allocation basis: duty and insurance follow value, freight follows weight, handling follows units. */
    public AllocationBasis defaultBasis() {
        return switch (this) {
            case DUTY, INSURANCE, FEES, BROKERAGE -> AllocationBasis.VALUE;
            case FREIGHT, DRAYAGE -> AllocationBasis.WEIGHT;
            case RECEIVING -> AllocationBasis.QUANTITY;
        };
    }
}
